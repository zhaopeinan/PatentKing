<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { DownOutlined, RightOutlined, ToolOutlined } from '@ant-design/icons-vue'
import {
  formatToolResult,
  getToolDisplayMeta,
  summarizeToolArgs,
} from '@/utils/chat/toolDisplay'
import { parsePkExportToolArgs } from '@/utils/chat/pkExport'

const props = defineProps<{
  id: string
  name: string
  args?: string
  result?: string
  elapsed?: number
  loading?: boolean
  needConfirm?: boolean
  matterId?: string
}>()

const emit = defineEmits<{
  (e: 'toolContent', value: unknown): void
  (e: 'toolAbort'): void
  (e: 'toolDirectExport', value: { name: string; args?: string }): void
}>()

const expanded = ref(false)
const now = ref(Date.now())
const startTime = ref(0)
let timer: ReturnType<typeof setInterval> | undefined

const meta = computed(() => getToolDisplayMeta(props.name))
const statusText = computed(() => {
  if (props.needConfirm) return '等待确认'
  if (props.loading) return '执行中'
  if (props.result) return '已完成'
  return '准备中'
})
const statusClass = computed(() => {
  if (props.needConfirm) return 'is-waiting'
  if (props.loading) return 'is-running'
  return 'is-done'
})
const displayElapsed = computed(() => {
  if (props.elapsed != null) return props.elapsed
  if (props.loading && startTime.value > 0) {
    return now.value - startTime.value
  }
  return undefined
})
const argsSummary = computed(() => summarizeToolArgs(props.name, props.args))
const resultSummary = computed(() => formatToolResult(props.name, props.result))
const activeStepIndex = computed(() => {
  if (!props.loading || props.needConfirm) return -1
  const ms = displayElapsed.value ?? 0
  if (props.name === 'pk_export_disclosure') {
    if (ms < 3000) return 0
    if (ms < 45000) return 1
    if (ms < 90000) return 2
    return 3
  }
  const stepMs = Math.max(4000, Math.floor(ms / meta.value.steps.length))
  return Math.min(meta.value.steps.length - 1, Math.floor(ms / stepMs))
})

const showSlowHint = computed(() =>
  props.loading
  && props.name === 'pk_export_disclosure'
  && (displayElapsed.value ?? 0) > 90_000,
)

const canDirectExport = computed(() =>
  props.name === 'pk_export_disclosure'
  && !!props.matterId
  && !!parsePkExportToolArgs(props.args),
)

const directExporting = ref(false)

watch(
  () => props.loading,
  (running) => {
    if (timer) {
      clearInterval(timer)
      timer = undefined
    }
    if (running) {
      startTime.value = Date.now()
      now.value = startTime.value
      expanded.value = true
      timer = setInterval(() => {
        now.value = Date.now()
      }, 1000)
    } else {
      startTime.value = 0
    }
  },
  { immediate: true },
)

watch(
  () => props.needConfirm,
  (waiting) => {
    if (waiting) expanded.value = true
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

const toggleExpanded = () => {
  expanded.value = !expanded.value
}

const handleConfirm = () => {
  emit('toolContent', { toolUseId: props.id, name: props.name, approved: true })
}

const handleCancel = () => {
  emit('toolContent', { toolUseId: props.id, name: props.name, approved: false })
}

const handleAbort = () => {
  emit('toolAbort')
}

const handleDirectExport = () => {
  if (directExporting.value || !canDirectExport.value) return
  directExporting.value = true
  emit('toolDirectExport', { name: props.name, args: props.args })
  setTimeout(() => { directExporting.value = false }, 8000)
}
</script>

<template>
  <div class="chat-message chat-message-assistant chat-tool-live">
    <div class="chat-message-bubble">
      <div class="chat-tool-panel chat-tool-panel--live">
        <div class="chat-tool-header" @click="toggleExpanded">
          <span class="chat-tool-header-icon"><ToolOutlined /></span>
          <span class="chat-tool-live-main">
            <span class="chat-tool-live-title">{{ meta.label }}</span>
            <span class="chat-tool-live-status" :class="statusClass">{{ statusText }}</span>
          </span>
          <span v-if="displayElapsed != null" class="chat-tool-live-elapsed">
            {{ Math.max(1, Math.round(displayElapsed / 1000)) }}s
          </span>
          <span class="chat-tool-header-arrow" :class="{ expanded }">
            <DownOutlined v-if="expanded" />
            <RightOutlined v-else />
          </span>
        </div>

        <div class="chat-tool-body" :class="{ 'is-expanded': expanded }">
          <p v-if="loading && meta.runningHint" class="chat-tool-live-hint">{{ meta.runningHint }}</p>

          <ul v-if="meta.steps.length" class="chat-tool-live-steps">
            <li
              v-for="(step, idx) in meta.steps"
              :key="step"
              :class="{
                'is-active': loading && activeStepIndex === idx,
                'is-done': !loading && result,
              }"
            >
              {{ step }}
            </li>
          </ul>

          <p v-if="showSlowHint" class="chat-tool-live-hint chat-tool-live-hint--warn">
            渲染时间较长（图多或正文较大）。可「终止」后点「直接导出」，无需等待助手。
          </p>

          <div v-if="loading && !needConfirm" class="chat-tool-call-actions">
            <AButton size="small" danger @click.stop="handleAbort">终止</AButton>
            <AButton
              v-if="canDirectExport"
              type="primary"
              size="small"
              :loading="directExporting"
              @click.stop="handleDirectExport"
            >
              直接导出
            </AButton>
          </div>

          <div v-if="needConfirm" class="chat-tool-call-actions">
            <AButton type="primary" size="small" @click.stop="handleConfirm">允许</AButton>
            <AButton size="small" @click.stop="handleCancel">禁止</AButton>
          </div>

          <div v-if="argsSummary" class="chat-tool-item">
            <div class="chat-tool-item-header">
              <span class="chat-tool-item-name">参数</span>
            </div>
            <pre class="chat-tool-item-code">{{ argsSummary }}</pre>
          </div>

          <div v-if="resultSummary" class="chat-tool-item">
            <div class="chat-tool-item-header">
              <span class="chat-tool-item-name">结果</span>
            </div>
            <pre class="chat-tool-item-code">{{ resultSummary }}</pre>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;

.chat-tool-live {
  margin-top: var(--spacing-sm);
}

.chat-tool-panel--live {
  border: 1px solid var(--color-border-extra-light);
  border-radius: var(--border-radius-md);
  padding: 6px 10px;
  background: rgba(0, 0, 0, 0.02);
}

.chat-tool-live-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.chat-tool-live-title {
  font-size: 13px;
  color: var(--color-text-primary);
  font-weight: 500;
}

.chat-tool-live-status {
  font-size: 11px;

  &.is-running {
    color: $chat-primary;
  }

  &.is-waiting {
    color: #d48806;
  }

  &.is-done {
    color: #389e0d;
  }
}

.chat-tool-live-elapsed {
  font-size: 11px;
  color: var(--color-text-placeholder);
  flex-shrink: 0;
}

.chat-tool-live-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--color-text-secondary);
  line-height: 1.5;

  &--warn {
    color: #d48806;
  }
}

.chat-tool-live-steps {
  margin: 0 0 10px;
  padding-left: 18px;
  font-size: 12px;
  color: var(--color-text-secondary);

  li {
    margin-bottom: 4px;

    &.is-active {
      color: $chat-primary;
      font-weight: 500;
    }

    &.is-done {
      color: #389e0d;
    }
  }
}

.chat-tool-call-actions {
  display: flex;
  gap: var(--spacing-sm);
  margin-bottom: 8px;
}
</style>
