<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import * as pkMatterApi from '@/api/pkMatter'
import type { ChatSessionVO, PkArtifactVO, PkMatterVO, PkMatterVersionVO, PkPatentPointItem } from '@/types'
import { parseChatBindings, parsePatentPointsMeta } from '@/utils/pkPatentPoints'
import { matterTypeDisplay } from '@/utils/pkMatterTypes'
import { runPool } from '@/utils/concurrentQueue'

const route = useRoute()
const router = useRouter()
const id = String(route.params.id)
const loading = ref(false)
const matter = ref<PkMatterVO | null>(null)
const versions = ref<PkMatterVersionVO[]>([])
const artifacts = ref<PkArtifactVO[]>([])
const hasModel = ref(true)
const hasStorage = ref(true)
const skillCount = ref(0)
const savingPoints = ref(false)
const newPointTitle = ref('')

const statusMap: Record<string, { text: string; color: string }> = {
  draft: { text: '草稿', color: 'default' },
  awaiting_confirm: { text: '待确认专利点', color: 'orange' },
  in_progress: { text: '撰写中', color: 'blue' },
  delivered: { text: '已交付', color: 'green' },
  archived: { text: '已归档', color: 'default' },
}

const artifactTypes = [
  { value: 'disclosure_md', label: '交底 Markdown' },
  { value: 'docx', label: 'Word / 五书' },
  { value: 'report', label: '分析报告' },
  { value: 'prior_art', label: '查新结果' },
  { value: 'figure', label: '附图' },
  { value: 'other', label: '其他' },
]

const bucketOptions = [
  { value: 'file', label: '拟申请' },
  { value: 'secret', label: '商业秘密' },
  { value: 'defer', label: '暂缓' },
]

const agentBound = computed(() => Boolean(matter.value?.agentDefinitionId))
const statusMeta = computed(() => statusMap[matter.value?.status || ''] || {
  text: matter.value?.status || '未知',
  color: 'default',
})
const points = reactive({
  inventory: [] as PkPatentPointItem[],
  selectedIds: [] as string[],
  tradeSecretIds: [] as string[],
  deferredIds: [] as string[],
  notes: '',
  submittedAt: '',
  confirmedAt: '',
})
const artifactForm = ref({
  name: '',
  artifactType: 'disclosure_md',
  storageUri: '',
})
const savingArtifact = ref(false)
const candidateSessions = ref<ChatSessionVO[]>([])
const importingSessionId = ref('')
const exportingDocx = ref(false)
const previewOpen = ref(false)
const previewTitle = ref('')
const previewContent = ref('')
const previewIsFile = ref(false)
const previewingArtifact = ref<PkArtifactVO | null>(null)
const downloadingId = ref('')
const figuresOpen = ref(false)
const figuresTitle = ref('')
const figuresRows = ref<Array<{
  index: number
  preview: string
  status: string
  engine: string
  source?: string
  progress?: string
}>>([])
const figuresMeta = ref<{ diagramMode?: string; figureCount?: number; failedCount?: number }>({})
const figuresArtifact = ref<PkArtifactVO | null>(null)
const regeneratingFigures = ref(false)
/** Tokenlab / mmdc 分图渲染并发数 */
const FIGURE_RENDER_CONCURRENCY = 3

const chatBindings = computed(() => parseChatBindings(matter.value?.metaJson))
const inventorsDisplay = computed(() => {
  const raw = matter.value?.inventorsJson
  if (!raw) return '—'
  try {
    const parsed = JSON.parse(raw)
    if (Array.isArray(parsed)) return parsed.filter(Boolean).join('、') || '—'
  } catch {
    /* keep raw */
  }
  return raw
})

function syncPointsFromMatter() {
  const parsed = parsePatentPointsMeta(matter.value?.metaJson)
  points.inventory = parsed?.inventory?.length ? parsed.inventory.map(row => ({ ...row })) : []
  points.selectedIds = [...(parsed?.selectedIds || [])]
  points.tradeSecretIds = [...(parsed?.tradeSecretIds || [])]
  points.deferredIds = [...(parsed?.deferredIds || [])]
  points.notes = parsed?.notes || ''
  points.submittedAt = parsed?.submittedAt || ''
  points.confirmedAt = parsed?.confirmedAt || ''
}

function pointsPayload() {
  return {
    inventory: points.inventory.map(row => ({ ...row })),
    selectedIds: points.inventory.filter(row => row.bucket === 'file').map(row => row.id),
    tradeSecretIds: points.inventory.filter(row => row.bucket === 'secret').map(row => row.id),
    deferredIds: points.inventory.filter(row => row.bucket === 'defer').map(row => row.id),
    notes: points.notes,
  }
}

async function load() {
  loading.value = true
  try {
    const [d, v, a, boot, sessions] = await Promise.all([
      pkMatterApi.detail(id),
      pkMatterApi.listVersions(id),
      pkMatterApi.listArtifacts(id),
      pkMatterApi.bootstrapStatus(),
      pkMatterApi.listCandidateSessions(id).catch(() => ({ data: { data: [] as ChatSessionVO[] } })),
    ])
    matter.value = d.data.data
    versions.value = v.data.data || []
    artifacts.value = a.data.data || []
    candidateSessions.value = sessions.data.data || []
    hasModel.value = Boolean(boot.data.data?.hasModel)
    hasStorage.value = boot.data.data?.hasStorage !== false
    skillCount.value = boot.data.data?.skills?.length || 0
    syncPointsFromMatter()
  } finally {
    loading.value = false
  }
}

function addPoint() {
  const title = newPointTitle.value.trim()
  if (!title) {
    message.warning('请填写专利点标题')
    return
  }
  const n = points.inventory.length + 1
  points.inventory.push({
    id: `P${n}`,
    title,
    bucket: 'file',
    summary: '',
  })
  newPointTitle.value = ''
}

function removePoint(index: number) {
  points.inventory.splice(index, 1)
}

async function submitPoints() {
  if (!points.inventory.length) {
    message.warning('请先添加至少一个专利点')
    return
  }
  savingPoints.value = true
  try {
    const res = await pkMatterApi.submitPatentPoints(id, pointsPayload())
    matter.value = res.data.data
    syncPointsFromMatter()
    message.success('已提交，等待确认后再成文')
  } finally {
    savingPoints.value = false
  }
}

async function confirmPoints() {
  if (!points.inventory.some(row => row.bucket === 'file')) {
    message.warning('请至少把一个点标为「拟申请」')
    return
  }
  savingPoints.value = true
  try {
    const res = await pkMatterApi.confirmPatentPoints(id, pointsPayload())
    matter.value = res.data.data
    syncPointsFromMatter()
    message.success('已确认专利点，可以打开对话成文')
  } finally {
    savingPoints.value = false
  }
}

async function addVersion() {
  await pkMatterApi.createVersion(id, { label: '手动版本' })
  message.success('已新增版本')
  await load()
}

async function ensureAndBind() {
  await pkMatterApi.ensureAgents()
  const res = await pkMatterApi.bindDefaultAgent(id)
  matter.value = res.data.data
  if (!matter.value?.agentDefinitionId) {
    message.warning('尚未找到默认智能体。请先在「技能」中上传 patentking-skills.zip（分类 pk），再点一次。')
    return
  }
  message.success('已绑定默认智能体')
}

async function saveArtifact() {
  if (!artifactForm.value.name.trim()) {
    message.warning('请填写产物名称')
    return
  }
  savingArtifact.value = true
  try {
    await pkMatterApi.createArtifact(id, {
      name: artifactForm.value.name.trim(),
      artifactType: artifactForm.value.artifactType,
      storageUri: artifactForm.value.storageUri.trim() || undefined,
      mime: artifactForm.value.artifactType === 'docx'
        ? 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
        : 'text/markdown',
    })
    message.success('已登记产物')
    artifactForm.value = { name: '', artifactType: artifactForm.value.artifactType, storageUri: '' }
    await load()
  } finally {
    savingArtifact.value = false
  }
}

function openChat(sessionId?: string) {
  if (!matter.value?.agentDefinitionId) {
    message.warning('请先绑定智能体')
    return
  }
  const sid = sessionId || chatBindings.value.lastSessionId
  let hash = `#/chat/${encodeURIComponent(matter.value.agentDefinitionId)}?matterId=${encodeURIComponent(id)}`
  if (sid) {
    hash += `&sessionId=${encodeURIComponent(sid)}`
  }
  const url = `${window.location.origin}${window.location.pathname}${hash}`
  window.open(url, '_blank')
}

async function importFromSession(sessionId: string) {
  if (!sessionId) {
    message.warning('没有可导入的对话')
    return
  }
  importingSessionId.value = sessionId
  try {
    const res = await pkMatterApi.importSession(id, sessionId)
    matter.value = res.data.data
    syncPointsFromMatter()
    message.success('已从对话同步专利点与交底草稿')
    await load()
    await autoExportWordIfNeeded()
  } catch (err) {
    console.warn(err)
    message.error('同步失败，请确认该对话属于当前案件智能体')
  } finally {
    importingSessionId.value = ''
  }
}

async function importLatest() {
  const latest = candidateSessions.value[0]
  const sid = latest ? String(latest.id) : chatBindings.value.lastSessionId
  await importFromSession(sid)
}

async function exportWord() {
  exportingDocx.value = true
  try {
    const res = await pkMatterApi.exportDocx(id, {
      title: matter.value?.title || 'disclosure',
      diagramMode: 'png',
    })
    message.success(`已机器渲染并登记：${res.data.data?.name || 'Word'}`)
    await load()
  } catch (err) {
    console.warn(err)
    message.error('导出失败。请确认 patent-tools 可用，且案件已有交底 Markdown。')
  } finally {
    exportingDocx.value = false
  }
}

function parseArtifactMeta(row: PkArtifactVO): Record<string, unknown> {
  if (!row.metaJson) return {}
  try {
    return JSON.parse(row.metaJson) as Record<string, unknown>
  } catch {
    return {}
  }
}

function extractMermaidBlocks(md: string): Array<{ index: number; preview: string; source: string }> {
  const blocks: Array<{ index: number; preview: string; source: string }> = []
  const re = /```mermaid[^\n]*\n([\s\S]*?)```/g
  let m: RegExpExecArray | null
  let i = 0
  while ((m = re.exec(md)) !== null) {
    i += 1
    const source = (m[1] || '').trim()
    const preview = source.split('\n').map(l => l.trim()).find(l => l && !/^(flowchart|graph|sequenceDiagram)/i.test(l)) || source.split('\n')[0] || '（空图）'
    blocks.push({ index: i, preview: preview.slice(0, 80), source })
  }
  return blocks
}

function latestDisclosureMarkdown(): string {
  const md = artifacts.value.find(a => a.artifactType === 'disclosure_md')
  if (!md) return ''
  const meta = parseArtifactMeta(md)
  return meta.content ? String(meta.content) : ''
}

function figureStatusLabel(status: string, progress?: string): string {
  if (progress && (status === 'pending' || status === 'rendering' || regeneratingFigures.value)) {
    return progress
  }
  if (status === 'ok') return '已嵌入'
  if (status === 'failed') return '源码残留'
  if (status === 'rendering') return '渲染中…'
  if (status === 'pending') return '排队中'
  return '未知'
}

function figureStatusColor(status: string): string {
  if (status === 'ok') return 'green'
  if (status === 'failed') return 'red'
  if (status === 'rendering') return 'processing'
  if (status === 'pending') return 'default'
  return 'default'
}

function figureRenderProgress(): string {
  if (!regeneratingFigures.value || !figuresRows.value.length) return ''
  const done = figuresRows.value.filter(r => r.status === 'ok' || r.status === 'failed').length
  const rendering = figuresRows.value.filter(r => r.status === 'rendering').length
  return `进度 ${done}/${figuresRows.value.length}（并发 ${FIGURE_RENDER_CONCURRENCY}，进行中 ${rendering}）`
}

function engineLabel(engine: string): string {
  if (engine === 'tokenlab') return 'Tokenlab 生图'
  if (engine === 'mmdc' || engine === 'mmdc_fallback') return engine === 'mmdc_fallback' ? 'mmdc（Tokenlab 回退）' : 'mermaid / mmdc'
  if (engine === 'source') return '未渲染（源码）'
  return engine || '未知'
}

function figureSummary(row: PkArtifactVO): string {
  const m = parseArtifactMeta(row)
  const ok = Number(m.figureCount || 0)
  const fail = Number(m.failedCount || 0)
  if (ok || fail) return `${ok} 成 / ${fail} 败`
  return '查看'
}

function openFigures(row: PkArtifactVO) {
  figuresArtifact.value = row
  figuresTitle.value = `插图状态 · ${row.name || '产物'}`
  const meta = parseArtifactMeta(row)
  figuresMeta.value = {
    diagramMode: String(meta.diagramMode || '—'),
    figureCount: Number(meta.figureCount || 0),
    failedCount: Number(meta.failedCount || 0),
  }
  const stored = Array.isArray(meta.figures) ? meta.figures as Array<Record<string, unknown>> : []
  if (stored.length) {
    figuresRows.value = stored.map((f, idx) => ({
      index: Number(f.index || idx + 1),
      preview: String(f.preview || ''),
      status: String(f.status || 'unknown'),
      engine: String(f.engine || ''),
      source: f.source ? String(f.source) : undefined,
    }))
  } else {
    const blocks = extractMermaidBlocks(latestDisclosureMarkdown())
    const okN = Number(meta.figureCount || 0)
    const failN = Number(meta.failedCount || 0)
    const mode = String(meta.diagramMode || 'png')
    figuresRows.value = blocks.map((b, idx) => {
      let status = 'unknown'
      let engine = mode === 'auto' ? 'tokenlab' : 'mmdc'
      if (failN > 0 && idx >= blocks.length - failN) {
        status = 'failed'
        engine = 'source'
      } else if (okN > 0 || mode) {
        status = 'ok'
      }
      return { ...b, status, engine }
    })
  }
  figuresOpen.value = true
}

async function reExportFigures(mode: 'png' | 'auto') {
  const md = latestDisclosureMarkdown()
  if (!md) {
    message.error('没有交底 Markdown，请先同步对话。')
    return
  }
  const blocks = extractMermaidBlocks(md)
  if (!blocks.length) {
    message.warning('未找到 mermaid 图示。')
    return
  }

  regeneratingFigures.value = true
  figuresRows.value = blocks.map(b => ({
    ...b,
    status: 'pending',
    engine: mode === 'auto' ? 'tokenlab' : 'mmdc',
    progress: '排队中',
  }))
  figuresMeta.value = { diagramMode: mode, figureCount: 0, failedCount: 0 }

  try {
    const sessionRes = await pkMatterApi.createExportSession(id, { diagramMode: mode })
    const sessionId = sessionRes.data.data?.sessionId
    if (!sessionId) {
      throw new Error(sessionRes.data.msg || '创建导出会话失败')
    }

    await runPool(figuresRows.value, FIGURE_RENDER_CONCURRENCY, async (row) => {
      row.status = 'rendering'
      row.progress = '渲染中…'
      try {
        const fr = await pkMatterApi.renderExportFigure(id, sessionId, row.index, {
          diagramMode: mode,
          force: true,
        })
        const data = fr.data.data
        const ok = Boolean(data?.ok)
        row.status = data?.status || (ok ? 'ok' : 'failed')
        row.engine = data?.engine || row.engine
        row.progress = ok ? '完成' : ((data?.message || '失败').slice(0, 80))
      } catch (err) {
        console.warn(err)
        row.status = 'failed'
        row.progress = '请求失败'
        row.engine = 'source'
      }
    })

    const fin = await pkMatterApi.finalizeExportSession(id, sessionId, {
      title: matter.value?.title || 'disclosure',
      diagramMode: mode,
    })

    const okN = figuresRows.value.filter(r => r.status === 'ok').length
    const failN = figuresRows.value.filter(r => r.status === 'failed').length
    figuresMeta.value = { diagramMode: mode, figureCount: okN, failedCount: failN }

    message.success(
      mode === 'auto'
        ? `Tokenlab 重渲完成（${okN} 成 / ${failN} 败）：${fin.data.data?.name || 'Word'}`
        : `mmdc 重渲完成（${okN} 成 / ${failN} 败）：${fin.data.data?.name || 'Word'}`,
    )
    await load()
    const newest = artifacts.value.find(a => a.artifactType === 'docx')
    if (newest) {
      figuresArtifact.value = newest
      openFigures(newest)
    }
  } catch (err) {
    console.warn(err)
    message.error(
      mode === 'auto'
        ? 'Tokenlab 重渲失败。请确认已在「系统设置 → PatentKing 生图」配置 API Key，且交底 Markdown 存在。'
        : 'mmdc 重渲失败。请确认 patent-tools 可用，且案件已有交底 Markdown。',
    )
  } finally {
    regeneratingFigures.value = false
  }
}

async function autoExportWordIfNeeded() {
  const hasMd = artifacts.value.some(a => a.artifactType === 'disclosure_md')
  const hasDocx = artifacts.value.some(a => a.artifactType === 'docx')
  if (!hasMd || hasDocx || exportingDocx.value) return
  exportingDocx.value = true
  try {
    const res = await pkMatterApi.exportDocx(id, {
      title: matter.value?.title || 'disclosure',
      diagramMode: 'png',
    })
    message.success(`已自动机器渲染 Word：${res.data.data?.name || ''}`)
    await load()
  } catch (err) {
    console.warn('[PK] 自动导出 Word 失败', err)
  } finally {
    exportingDocx.value = false
  }
}

function isDownloadableArtifact(row: PkArtifactVO): boolean {
  const t = (row.artifactType || '').toLowerCase()
  if (t === 'docx' || t === 'pdf' || t === 'file') return true
  const uri = row.storageUri || ''
  if (!uri) return false
  if (uri.startsWith('chat-session:') || uri.startsWith('http://') || uri.startsWith('https://')) return false
  return uri.endsWith('.docx') || uri.endsWith('.pdf')
}

async function downloadArtifact(row: PkArtifactVO) {
  if (!row.id) {
    message.warning('产物 ID 无效')
    return
  }
  downloadingId.value = String(row.id)
  try {
    const res = await pkMatterApi.downloadArtifact(id, String(row.id))
    const blob = res.data as unknown as Blob
    if (!(blob instanceof Blob) || blob.size === 0) {
      message.error('下载失败：文件为空')
      return
    }
    // 错误时后端可能返回 JSON blob
    if (blob.type && blob.type.includes('application/json')) {
      const text = await blob.text()
      try {
        const err = JSON.parse(text) as { msg?: string }
        message.error(err.msg || '下载失败')
      } catch {
        message.error('下载失败')
      }
      return
    }
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = row.name || 'artifact.docx'
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
    message.success('已开始下载')
  } catch (err) {
    console.warn(err)
    message.error('下载失败')
  } finally {
    downloadingId.value = ''
  }
}

function previewArtifact(row: PkArtifactVO) {
  previewTitle.value = row.name
  previewingArtifact.value = row
  previewIsFile.value = isDownloadableArtifact(row)
  if (previewIsFile.value) {
    previewContent.value = [
      `类型：${row.artifactType || '—'}`,
      `路径：${row.storageUri || '—'}`,
      `时间：${row.createdAt || '—'}`,
      '',
      '这是 Word/文件产物，请点击下方「下载」保存到本地。',
    ].join('\n')
  } else {
    previewContent.value = artifactContent(row)
  }
  previewOpen.value = true
}

function artifactContent(row: PkArtifactVO): string {
  if (!row.metaJson) return '暂无正文。对话里的交底同步后会出现在这里。'
  try {
    const meta = JSON.parse(row.metaJson) as Record<string, unknown>
    if (meta.content) return String(meta.content)
  } catch {
    /* ignore */
  }
  return '暂无正文。'
}

onMounted(load)
</script>

<template>
  <div class="pk-matter-detail" v-if="matter">
    <a-page-header :title="matter.title" @back="router.push('/patent-matters')">
      <template #extra>
        <a-tag>{{ matterTypeDisplay(matter.matterType, matter.metaJson) }}</a-tag>
        <a-tag :color="statusMeta.color">{{ statusMeta.text }}</a-tag>
        <a-button v-if="!agentBound" @click="ensureAndBind">绑定默认智能体</a-button>
        <a-button type="primary" :disabled="!agentBound" @click="openChat()">打开对话</a-button>
        <a-button :disabled="!agentBound" :loading="!!importingSessionId" @click="importLatest">从对话同步</a-button>
        <a-button :loading="exportingDocx" @click="exportWord">导出 Word（自动渲染）</a-button>
      </template>
    </a-page-header>

    <a-alert
      v-if="!hasModel"
      type="warning"
      show-icon
      style="margin: 0 20px 12px"
      message="尚未配置大模型。请到「模型」填写你自己的 API（供应商 + 模型），然后到「智能体」给 PatentKing 助手选择该模型。"
    />
    <a-alert
      v-else-if="!hasStorage"
      type="warning"
      show-icon
      style="margin: 0 20px 12px"
      message="尚未启用文件存储。对话传附件会失败。请到「运维管理 → 存储管理」新增 LOCAL 协议并设为有效，或在案件页点一次「绑定默认智能体」。"
    />
    <a-alert
      v-else-if="skillCount === 0"
      type="info"
      show-icon
      style="margin: 0 20px 12px"
      message="尚未导入专利技能包。请到「技能」上传 patent-skills/_packaged/patentking-skills.zip，分类填 pk。"
    />
    <a-alert
      v-if="matter.status === 'awaiting_confirm'"
      type="warning"
      show-icon
      style="margin: 0 20px 12px"
      message="专利点待确认。请在本页勾选拟申请 / 商业秘密，或在对话里提交 APIP 表单并允许确认工具后，才能成文。"
    />

    <a-spin :spinning="loading">
      <a-descriptions bordered :column="2" style="margin: 16px 20px">
        <a-descriptions-item label="备注">{{ matter.remark || '—' }}</a-descriptions-item>
        <a-descriptions-item label="工作区">{{ matter.workspaceRelPath || '尚未绑定' }}</a-descriptions-item>
        <a-descriptions-item label="智能体">{{ matter.agentDefinitionId || '未绑定' }}</a-descriptions-item>
        <a-descriptions-item label="发明人">{{ inventorsDisplay }}</a-descriptions-item>
      </a-descriptions>

      <a-card title="专利点确认闸门" style="margin: 0 20px 16px" size="small">
        <p class="hint">
          交底成文前必须确认「写哪些 / 当商业秘密 / 暂缓」。对话里会弹出 APIP 表单，并暂停在「专利点确认闸门」工具；也可在本页直接提交。
        </p>
        <div class="point-add">
          <a-input v-model:value="newPointTitle" placeholder="新增专利点标题，如 自适应批处理调度" @pressEnter="addPoint" />
          <a-button @click="addPoint">添加</a-button>
        </div>
        <a-empty v-if="!points.inventory.length" description="还没有专利点。可点「从对话同步」，或在这里手工添加。" />
        <a-table v-else row-key="id" size="small" :pagination="false" :data-source="points.inventory">
          <a-table-column title="编号" data-index="id" :width="80" />
          <a-table-column title="标题" data-index="title" />
          <a-table-column title="摘要" data-index="summary" ellipsis />
          <a-table-column title="处置" :width="160">
            <template #default="{ record }">
              <a-select v-model:value="record.bucket" style="width: 140px" :options="bucketOptions" />
            </template>
          </a-table-column>
          <a-table-column title="" :width="70">
            <template #default="{ index }">
              <a-button type="link" danger size="small" @click="removePoint(index)">删除</a-button>
            </template>
          </a-table-column>
        </a-table>
        <a-textarea
          v-model:value="points.notes"
          style="margin-top: 12px"
          :rows="2"
          placeholder="补充意见，如某点仅内部使用、不要写进交底"
        />
        <div class="point-actions">
          <a-button :loading="savingPoints" @click="submitPoints">提交待确认</a-button>
          <a-button type="primary" :loading="savingPoints" @click="confirmPoints">确认可成文</a-button>
          <span v-if="points.submittedAt" class="muted">已提交 {{ points.submittedAt }}</span>
          <span v-if="points.confirmedAt" class="muted">已确认 {{ points.confirmedAt }}</span>
        </div>
      </a-card>

      <a-card title="关联对话" style="margin: 0 20px 16px" size="small">
        <p class="hint">对话内容存在智能体会话里，不会自动出现在本页。点「同步」会把专利点和交底草稿写回本案件。</p>
        <a-empty v-if="!candidateSessions.length" description="这个智能体下还没有对话。请先打开对话。" />
        <a-table v-else row-key="id" size="small" :pagination="false" :data-source="candidateSessions">
          <a-table-column title="标题" data-index="title">
            <template #default="{ record }">
              {{ record.title || '新对话' }}
              <a-tag v-if="String(record.id) === chatBindings.lastSessionId" color="blue" style="margin-left: 8px">已绑定</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="更新时间" data-index="updatedAt" :width="190" />
          <a-table-column title="" :width="200">
            <template #default="{ record }">
              <a-button type="link" size="small" @click="openChat(String(record.id))">继续</a-button>
              <a-button
                type="link"
                size="small"
                :loading="importingSessionId === String(record.id)"
                @click="importFromSession(String(record.id))"
              >同步到本案</a-button>
            </template>
          </a-table-column>
        </a-table>
      </a-card>

      <a-card title="版本" style="margin: 0 20px 16px" size="small">
        <template #extra>
          <a-button size="small" @click="addVersion">新建版本</a-button>
        </template>
        <a-empty v-if="!versions.length" description="暂无版本，可先创建 v1 目录占位" />
        <a-timeline v-else>
          <a-timeline-item v-for="item in versions" :key="item.id">
            v{{ item.versionNo }} {{ item.label || '' }}
            <span class="muted">{{ item.createdAt }}</span>
          </a-timeline-item>
        </a-timeline>
      </a-card>

      <a-card title="产物" style="margin: 0 20px 16px" size="small">
        <div class="artifact-form">
          <a-input v-model:value="artifactForm.name" placeholder="产物名称，如 交底书 v1" style="width: 220px" />
          <a-select v-model:value="artifactForm.artifactType" style="width: 160px" :options="artifactTypes" />
          <a-input v-model:value="artifactForm.storageUri" placeholder="可选：文件路径或链接" style="flex: 1" />
          <a-button type="primary" :loading="savingArtifact" @click="saveArtifact">登记产物</a-button>
        </div>
        <a-empty v-if="!artifacts.length" description="对话生成交底后点「从对话同步」，或在这里手工登记路径" />
        <a-table v-else row-key="id" size="small" :pagination="false" :data-source="artifacts">
          <a-table-column title="名称" data-index="name" />
          <a-table-column title="类型" data-index="artifactType" :width="140" />
          <a-table-column title="插图" :width="120">
            <template #default="{ record }">
              <template v-if="record.artifactType === 'docx'">
                <a-button type="link" size="small" @click="openFigures(record)">
                  {{ figureSummary(record) }}
                </a-button>
              </template>
              <span v-else class="muted">—</span>
            </template>
          </a-table-column>
          <a-table-column title="路径" data-index="storageUri" ellipsis />
          <a-table-column title="时间" data-index="createdAt" :width="180" />
          <a-table-column title="" :width="200">
            <template #default="{ record }">
              <a-button
                v-if="isDownloadableArtifact(record)"
                type="link"
                size="small"
                :loading="downloadingId === String(record.id)"
                @click="downloadArtifact(record)"
              >下载</a-button>
              <a-button
                v-if="record.artifactType === 'docx'"
                type="link"
                size="small"
                @click="openFigures(record)"
              >插图</a-button>
              <a-button type="link" size="small" @click="previewArtifact(record)">查看</a-button>
            </template>
          </a-table-column>
        </a-table>
      </a-card>
    </a-spin>

    <a-modal v-model:open="previewOpen" :title="previewTitle" width="720px">
      <pre class="preview-body">{{ previewContent }}</pre>
      <template #footer>
        <a-button
          v-if="previewIsFile && previewingArtifact"
          type="primary"
          :loading="downloadingId === String(previewingArtifact.id)"
          @click="downloadArtifact(previewingArtifact)"
        >下载 Word</a-button>
        <a-button @click="previewOpen = false">关闭</a-button>
      </template>
    </a-modal>

    <a-modal v-model:open="figuresOpen" :title="figuresTitle" width="860px">
      <p class="hint">
        模式：{{ figuresMeta.diagramMode || '—' }}
        · 成功 {{ figuresMeta.figureCount ?? 0 }}
        · 失败 {{ figuresMeta.failedCount ?? 0 }}
        <span v-if="figureRenderProgress()" class="render-progress">{{ figureRenderProgress() }}</span>
      </p>
      <a-empty v-if="!figuresRows.length" description="未找到 mermaid 图示。请确认已同步交底 Markdown，或重新导出 Word。" />
      <a-table v-else row-key="index" size="small" :pagination="false" :data-source="figuresRows">
        <a-table-column title="#" data-index="index" :width="50" />
        <a-table-column title="节点预览" data-index="preview" ellipsis />
        <a-table-column title="状态" :width="140">
          <template #default="{ record }">
            <a-tag :color="figureStatusColor(record.status)">
              {{ figureStatusLabel(record.status, record.progress) }}
            </a-tag>
          </template>
        </a-table-column>
        <a-table-column title="引擎" :width="160">
          <template #default="{ record }">
            {{ engineLabel(record.engine) }}
          </template>
        </a-table-column>
      </a-table>
      <template #footer>
        <a-button
          :loading="regeneratingFigures"
          @click="reExportFigures('png')"
        >用 mermaid 重渲 Word</a-button>
        <a-button
          type="primary"
          :loading="regeneratingFigures"
          @click="reExportFigures('auto')"
        >用 Tokenlab 重渲 Word</a-button>
        <a-button
          v-if="figuresArtifact && isDownloadableArtifact(figuresArtifact)"
          :loading="downloadingId === String(figuresArtifact.id)"
          @click="downloadArtifact(figuresArtifact)"
        >下载当前 Word</a-button>
        <a-button @click="figuresOpen = false">关闭</a-button>
      </template>
    </a-modal>
  </div>
</template>

<style scoped>
.muted {
  color: #8c8c8c;
  margin-left: 8px;
  font-size: 12px;
}
.hint {
  color: #4e5969;
  margin: 0 0 12px;
  font-size: 13px;
}
.render-progress {
  margin-left: 8px;
  color: #1677ff;
}
.point-add {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.point-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 12px;
  flex-wrap: wrap;
}
.artifact-form {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
}
.preview-body {
  max-height: 60vh;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
  margin: 0;
}
</style>
