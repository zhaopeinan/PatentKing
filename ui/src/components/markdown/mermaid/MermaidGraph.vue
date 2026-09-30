<template>
  <div
    ref="containerRef"
    class="mermaid-enhanced"
    :class="{ 'md-code-fullscreen': isFullscreen }"
    :style="{ backgroundColor: '#F9FAFB' }"
  >
    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="view-toggle">
        <button
          class="toggle-btn"
          :class="{ active: viewMode === 'diagram' }"
          @click="viewMode = 'diagram'">图表</button>
        <button
          class="toggle-btn"
          :class="{ active: viewMode === 'code' }"
          @click="viewMode = 'code'">代码</button>
      </div>

      <div class="actions">
        <!-- 图表模式下的缩放控件 -->
        <template v-if="viewMode === 'diagram' && svgContent">
          <button class="icon-btn" @click="zoomOut" :disabled="scale <= 0.5">
            缩小
          </button>
          <span class="scale-value">{{ Math.round(scale * 100) }}%</span>
          <button class="icon-btn" @click="zoomIn" :disabled="scale >= 2.0">
            放大
          </button>
          <button class="icon-btn" @click="resetZoom">重置</button>
        </template>

        <!-- 代码模式下的复制按钮 -->
        <template v-if="viewMode === 'code'">
          <button class="icon-btn" :class="{ copied: copySuccess }" @click="copyCode">
            {{ copySuccess ? '已复制' : '复制' }}
          </button>
        </template>

        <!-- 全屏按钮 -->
        <button class="icon-btn" @click="toggleFullscreen">
          <span v-if="!isFullscreen">全屏</span>
          <span v-else>退出全屏</span>
        </button>
      </div>
    </div>

    <!-- 内容区域 -->
    <div
      class="content-pane"
      ref="contentPaneRef"
      @mousedown="handlePanStart"
      @mousemove="handlePanMove"
      @mouseup="handlePanEnd"
      @mouseleave="handlePanEnd"
    >
      <!-- 图表视图 -->
      <div
        v-show="viewMode === 'diagram'"
        class="diagram-view"
        :class="{ 'panning': isPanning }"
        :style="{
          transform: `translate(${panX}px, ${panY}px) scale(${scale})`,
          cursor: isPanning ? 'grabbing' : 'grab'
        }"
      >
        <!-- 渲染中 → 加载动画 -->
        <div v-if="isRendering && !svgContent" class="placeholder">
          <div class="loading-spinner"></div>
          <span>{{ renderStatus }}</span>
        </div>
        <!-- 已渲染 → SVG 图表 -->
        <div v-else-if="svgContent" ref="svgWrapper" class="svg-wrapper">
          <div v-if="isTokenlabAuto" class="tokenlab-auto-badge overlay">
            结构预览 · Word 导出时由 Tokenlab 生图
          </div>
          <div v-html="svgContent"></div>
        </div>
        <!-- 渲染失败 → 代码备选 + 手动重试 -->
        <div v-else class="code-fallback">
          <div v-if="isTokenlabAuto" class="tokenlab-auto-pane">
            <div class="tokenlab-auto-badge">Tokenlab 自动生成</div>
            <p class="tokenlab-auto-desc">
              Word 导出时将调用 Tokenlab 生图。下方为结构源码（对话预览渲染失败时回退显示）。
            </p>
          </div>
          <pre class="code-block">{{ displayCode }}</pre>
          <div v-if="renderError" class="error-panel">
            <div class="error-title">Mermaid 解析失败</div>
            <pre class="error-detail">{{ renderError }}</pre>
          </div>
          <button
            v-if="showManualRetry"
            class="mermaid-retry-btn"
            :disabled="retrying"
            @click="onManualRetry"
          >
            {{ retrying ? '已请求助手修复…' : '重新生成' }}
          </button>
          <p v-if="retryHint" class="retry-hint">{{ retryHint }}</p>
        </div>
      </div>

      <!-- 代码视图 -->
      <div v-show="viewMode === 'code'" class="code-view">
        <pre class="code-block">{{ displayCode }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, computed } from 'vue'
import { useDebounceFn } from '@vueuse/core'
import { message } from 'ant-design-vue'
import mermaid from 'mermaid'
import {
  MERMAID_MAX_RENDER_ATTEMPTS,
  buildMermaidRenderAttempts,
  decodeMermaidHtmlEntities,
  type MermaidRetryPayload,
} from '@/utils/chat/mermaid'

// ---------- Mermaid 初始化 ----------
mermaid.initialize({
  startOnLoad: false,
  theme: 'default',
  securityLevel: 'loose',
  fontFamily: 'monospace',
  flowchart: {
    useMaxWidth: true,
    htmlLabels: true,
    curve: 'basis',
  },
})

// ---------- Props ----------
const props = defineProps<{
  /** Mermaid 代码块原始内容 */
  code: string
  /** 历史消息只读，不显示「重新生成」 */
  disabled?: boolean
  /** png | auto — auto 时不做客户端 mermaid 渲染 */
  diagramMode?: string
}>()

const emit = defineEmits<{
  retry: [payload: import('@/utils/chat/mermaid').MermaidRetryPayload]
}>()

// ---------- 响应式状态 ----------
const containerRef = ref<HTMLElement>()
const contentPaneRef = ref<HTMLElement>()
const svgWrapper = ref<HTMLElement>()

const viewMode = ref<'diagram' | 'code'>('diagram')
const svgContent = ref<string | null>(null)
const renderError = ref<string | null>(null)
const isRendering = ref(false)
const attemptNo = ref(0)
const manualRetryAvailable = ref(false)
const retrying = ref(false)
const retryHint = ref<string | null>(null)
const scale = ref(1.0)
const isFullscreen = ref(false)
const copySuccess = ref(false)

// 拖拽相关状态
const panX = ref(0)
const panY = ref(0)
const isPanning = ref(false)
const panStartX = ref(0)
const panStartY = ref(0)
const panStartPosX = ref(0)
const panStartPosY = ref(0)

let renderId = 0
const lastAutoFixAttempts = ref<string[]>([])

const displayCode = computed(() => decodeMermaidHtmlEntities(props.code))
const isTokenlabAuto = computed(() => {
  const m = (props.diagramMode || 'png').toLowerCase()
  return m === 'auto' || m === 'tokenlab' || m === 'image-api'
})
const showManualRetry = computed(() => manualRetryAvailable.value && !props.disabled && !isTokenlabAuto.value)
const renderStatus = computed(() => {
  if (attemptNo.value <= 1) return '渲染中...'
  return `语法无效，正在第 ${attemptNo.value}/${MERMAID_MAX_RENDER_ATTEMPTS} 次自动修复...`
})

async function parseMermaid(code: string): Promise<void> {
  const result = mermaid.parse(code)
  if (result && typeof (result as Promise<unknown>).then === 'function') {
    await result
  }
}

function sleep(ms: number) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

// ---------- 渲染逻辑：最多自动尝试 3 次（原文 + 引号修复 + 符号替换）----------
const doRender = async () => {
  // auto 模式：对话内仍做 Mermaid 结构预览；Tokenlab 生图仅在 Word 导出时发生
  if (!props.code) {
    svgContent.value = null
    renderError.value = null
    manualRetryAvailable.value = false
    return
  }

  const currentRenderId = ++renderId
  const candidates = buildMermaidRenderAttempts(props.code)
  if (candidates.length === 0) {
    svgContent.value = null
    renderError.value = 'Mermaid 内容为空'
    manualRetryAvailable.value = !props.disabled && !isTokenlabAuto.value
    return
  }

  isRendering.value = true
  renderError.value = null
  manualRetryAvailable.value = false
  retryHint.value = null
  svgContent.value = null
  lastAutoFixAttempts.value = []

  let lastError = 'Mermaid 语法无效或不完整'
  const tried: string[] = []
  try {
    for (let i = 0; i < MERMAID_MAX_RENDER_ATTEMPTS; i++) {
      if (currentRenderId !== renderId) return
      attemptNo.value = i + 1
      const source = candidates[Math.min(i, candidates.length - 1)] as string
      tried.push(source)
      try {
        await parseMermaid(source)
        const id = `mermaid-${Date.now()}-${Math.random().toString(36).substring(2, 11)}`
        const { svg } = await mermaid.render(id, source)
        if (currentRenderId !== renderId) return
        svgContent.value = svg
        renderError.value = null
        manualRetryAvailable.value = false
        lastAutoFixAttempts.value = tried
        return
      } catch (error) {
        lastError = errorText(error)
        if (i < MERMAID_MAX_RENDER_ATTEMPTS - 1) {
          await sleep(160)
        }
      }
    }

    if (currentRenderId !== renderId) return
    console.error('Mermaid 渲染失败（已自动重试 3 次）:', lastError)
    svgContent.value = null
    renderError.value = lastError
    lastAutoFixAttempts.value = tried
    manualRetryAvailable.value = !isTokenlabAuto.value
  } finally {
    if (currentRenderId === renderId) {
      isRendering.value = false
    }
  }
}

function onManualRetry() {
  if (retrying.value || props.disabled || isTokenlabAuto.value) return
  retrying.value = true
  retryHint.value = '已请求助手修复 Mermaid 语法，请等待回复中的新代码块。'
  message.info('已请求助手修复 Mermaid 语法')
  const payload: MermaidRetryPayload = {
    code: displayCode.value,
    error: renderError.value || '未知解析错误',
    autoFixAttempts: lastAutoFixAttempts.value.length
      ? [...lastAutoFixAttempts.value]
      : buildMermaidRenderAttempts(props.code),
  }
  emit('retry', payload)
  setTimeout(() => { retrying.value = false }, 8000)
}

const debouncedRender = useDebounceFn(doRender, 300)

// ---------- 缩放控制 ----------
const zoomIn = () => {
  scale.value = Math.min(2.0, scale.value + 0.2)
}

const zoomOut = () => {
  scale.value = Math.max(0.5, scale.value - 0.2)
}

const resetZoom = () => {
  scale.value = 1.0
  panX.value = 0
  panY.value = 0
}

// ---------- 拖拽控制 ----------
const handlePanStart = (e: MouseEvent) => {
  // 只在图表视图且有内容时启用拖拽
  if (viewMode.value !== 'diagram' || !svgContent.value) return

  // 只有按住鼠标左键时才启用拖拽
  if (e.button !== 0) return

  // 防止拖拽时选中文本
  e.preventDefault()

  isPanning.value = true
  panStartX.value = e.clientX
  panStartY.value = e.clientY
  panStartPosX.value = panX.value
  panStartPosY.value = panY.value
}

const handlePanMove = (e: MouseEvent) => {
  if (!isPanning.value) return

  e.preventDefault()

  const deltaX = e.clientX - panStartX.value
  const deltaY = e.clientY - panStartY.value

  panX.value = panStartPosX.value + deltaX
  panY.value = panStartPosY.value + deltaY
}

const handlePanEnd = () => {
  isPanning.value = false
}

// ---------- 复制代码 ----------
const copyCode = async () => {
  try {
    await navigator.clipboard.writeText(displayCode.value)
    copySuccess.value = true
    setTimeout(() => {
      copySuccess.value = false
    }, 2000)
  } catch (err) {
    console.error('复制失败:', err)
  }
}

// ---------- CSS 全屏切换（添加/移除类）----------
const toggleFullscreen = () => {
  isFullscreen.value = !isFullscreen.value
  // 切换全屏时重置位置
  panX.value = 0
  panY.value = 0
}

// 监听 ESC 键退出全屏
const handleKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Escape' && isFullscreen.value) {
    isFullscreen.value = false
  }
}

// 监听代码变化
watch(
  () => props.code,
  () => {
    // 重置缩放和位置
    resetZoom()
    // 防抖渲染
    debouncedRender()
  },
  { immediate: false }
)

// 监听视图模式切换，重置拖拽状态
watch(viewMode, () => {
  isPanning.value = false
})

onMounted(() => {
  doRender()
  document.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  document.removeEventListener('keydown', handleKeydown)
})
</script>

<style scoped>
.mermaid-enhanced {
  display: flex;
  flex-direction: column;
  margin: 0.8em 0;
  border-radius: 8px;
  overflow: hidden;
  background-color: #fafbfc;
  border: 1px solid #e9ecef;
}

/* CSS 全屏样式 - 通过添加类实现网页全屏效果 */
.mermaid-enhanced.md-code-fullscreen {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 9999;
  margin: 0;
  border-radius: 0;
  border: none;
  display: flex;
  flex-direction: column;
  background-color: #fafbfc !important;
}

/* 全屏时的内容面板自动撑满剩余空间 */
.mermaid-enhanced.md-code-fullscreen .content-pane {
  flex: 1;
  overflow: auto;
  padding: 20px;
}

/* 工具栏 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 14px;
  background-color: #FAFBFC;
  flex-shrink: 0;
}

.view-toggle {
  display: flex;
  gap: 4px;
  padding: 2px;
  border-radius: 6px;
}

.toggle-btn {
  background: transparent;
  border: none;
  color: #666;
  font-size: 0.78em;
  padding: 4px 12px;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
  font-weight: 500;
}

.toggle-btn:hover {
  color: #111827;
  background-color: #e5e7eb;
}

.toggle-btn.active {
  background-color: #ffffff;
  color: #2563eb;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.toggle-btn svg {
  stroke-width: 1.8;
}

.actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 4px 8px;
  font-size: 0.78em;
  color: #4b5563;
  background: transparent;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
  font-weight: 500;
}

.icon-btn:hover {
  color: #111827;
  background-color: #e5e7eb;
}

.icon-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.icon-btn.copied {
  color: #10b981;
  border-color: #10b981;
  background-color: #f0fdf4;
}

.scale-value {
  color: #9ca3af;
  font-size: 0.78em;
  min-width: 45px;
  text-align: center;
}

/* 内容面板 */
.content-pane {
  flex: 1;
  overflow: hidden;
  padding: 20px;
  background-color: #fafbfc;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  min-height: 240px;
  position: relative;
  user-select: none;
}

/* 图表视图 */
.diagram-view {
  display: inline-block;
  transform-origin: 0 0;
  transition: transform 0.1s ease;
  min-width: 100%;
  cursor: grab;
}

.diagram-view.panning {
  transition: none;
  cursor: grabbing;
}

.svg-wrapper {
  position: relative;
  display: inline-block;
  min-width: 100%;
  pointer-events: none; /* 让鼠标事件穿透到父元素，便于拖拽 */
}

.svg-wrapper :deep(svg) {
  max-width: 100%;
  height: auto;
  display: block;
  margin: 0 auto;
  background-color: transparent;
  pointer-events: none; /* 让鼠标事件穿透到父元素，便于拖拽 */
}

.tokenlab-auto-badge.overlay {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 2;
  margin-bottom: 0;
  pointer-events: none;
}

/* 代码视图 / 备选显示 */
.code-view,
.code-fallback {
  width: 100%;
  user-select: text;
}

.code-block {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: #1f2937;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-x: auto;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
  user-select: text;
}

.error-message {
  margin-top: 12px;
  padding: 10px 14px;
  background-color: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 8px;
  color: #b91c1c;
  font-size: 13px;
  font-family: monospace;
  user-select: text;
}

.error-panel {
  margin-top: 12px;
  padding: 10px 14px;
  background-color: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 8px;
}

.error-title {
  font-size: 13px;
  font-weight: 600;
  color: #b91c1c;
  margin-bottom: 6px;
}

.error-detail {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: #991b1b;
  white-space: pre-wrap;
  word-break: break-word;
  user-select: text;
}

.retry-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #64748b;
}

.tokenlab-auto-pane {
  width: 100%;
  padding: 12px;
}

.tokenlab-auto-badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 8px;
}

.tokenlab-auto-desc {
  margin: 0 0 10px;
  font-size: 13px;
  color: #475569;
  line-height: 1.5;
}

.mermaid-retry-btn {
  display: inline-flex;
  align-items: center;
  margin-top: 10px;
  padding: 4px 12px;
  font-size: 12px;
  color: #1677ff;
  background: #fff;
  border: 1px solid #1677ff;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.mermaid-retry-btn:hover:not(:disabled) {
  color: #fff;
  background: #1677ff;
}

.mermaid-retry-btn:disabled {
  color: #bfbfbf;
  border-color: #d9d9d9;
  cursor: not-allowed;
}

/* 占位与加载 */
.placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px;
  color: #6b7280;
  gap: 12px;
  user-select: none;
}

.loading-spinner {
  width: 24px;
  height: 24px;
  border: 2px solid #e5e7eb;
  border-top-color: #2563eb;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* 暗色模式适配 (保留原有效果) */
@media (prefers-color-scheme: dark) {
  .mermaid-enhanced {
    border-color: #374151;
  }

  .toolbar {
    background-color: #1f2937;
    border-bottom-color: #374151;
  }

  .view-toggle {
    background-color: #374151;
  }

  .toggle-btn {
    color: #9ca3af;
  }

  .toggle-btn:hover {
    color: #f3f4f6;
    background-color: #4b5563;
  }

  .toggle-btn.active {
    background-color: #111827;
    color: #60a5fa;
  }

  .icon-btn {
    color: #9ca3af;
    border-color: #4b5563;
  }

  .icon-btn:hover:not(:disabled) {
    background-color: #374151;
    border-color: #6b7280;
    color: #f3f4f6;
  }

  .scale-value {
    color: #9ca3af;
  }

  .divider {
    background-color: #4b5563;
  }

  .content-pane {
    background-color: #111827;
  }

  .code-block {
    background-color: #1f2937;
    border-color: #374151;
    color: #e5e7eb;
  }

  .error-message {
    background-color: #450a0a;
    border-color: #7f1d1d;
    color: #fca5a5;
  }

  /* 深色模式下的 SVG 标签颜色 (继承原有样式) */
  .mermaid-enhanced :deep(.label) {
    fill: #e0e0e0;
  }
  .mermaid-enhanced :deep(.cluster-label text) {
    fill: #e0e0e0;
  }
  .mermaid-enhanced :deep(.node rect),
  .mermaid-enhanced :deep(.node circle),
  .mermaid-enhanced :deep(.node polygon) {
    stroke: #6b7280;
  }
}

/* 亮色模式 SVG 辅助 */
.mermaid-enhanced :deep(.label) {
  fill: #1f2937;
}
.mermaid-enhanced :deep(.cluster-label text) {
  fill: #1f2937;
}
</style>
