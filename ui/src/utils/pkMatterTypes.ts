/** PatentKing 案件一级类型与交底下场景（细意图） */

export const PK_PRIMARY_MATTER_TYPES = [
  { label: '交底撰写', value: 'disclosure', hint: '挖点、查新、成文；解读/估值等用下方场景' },
  { label: '论文转专利', value: 'paper2patent', hint: '论文/长文 → 五书文本' },
  { label: '侵权分析', value: 'radar', hint: '完整公开号 → claim chart' },
  { label: '审查答复', value: 'oa', hint: '审查意见答辩提纲与修改建议' },
] as const

/** 交底撰写下的场景（存 metaJson.scene，对话可再判意图） */
export const PK_DISCLOSURE_SCENES = [
  { label: '成文交底', value: 'write' },
  { label: '口述引导', value: 'oral' },
  { label: '专利解读', value: 'read' },
  { label: '价值评估', value: 'valuate' },
  { label: '尽调清单', value: 'dd' },
  { label: '供需匹配', value: 'match' },
] as const

const LEGACY_TYPE_TO_SCENE: Record<string, string> = {
  read: 'read',
  valuate: 'valuate',
  dd: 'dd',
  match: 'match',
}

const TYPE_LABELS: Record<string, string> = {
  disclosure: '交底撰写',
  paper2patent: '论文转专利',
  radar: '侵权分析',
  oa: '审查答复',
  // legacy display
  read: '交底撰写',
  valuate: '交底撰写',
  dd: '交底撰写',
  match: '交底撰写',
}

const SCENE_LABELS: Record<string, string> = Object.fromEntries(
  PK_DISCLOSURE_SCENES.map(s => [s.value, s.label]),
)

export function normalizeMatterType(raw?: string | null): string {
  const t = (raw || '').trim()
  if (!t) return 'disclosure'
  if (t in LEGACY_TYPE_TO_SCENE) return 'disclosure'
  if (PK_PRIMARY_MATTER_TYPES.some(x => x.value === t)) return t
  return 'disclosure'
}

export function inferSceneFromMatter(matterType?: string | null, metaJson?: string | null): string {
  const fromMeta = readSceneFromMeta(metaJson)
  if (fromMeta) return fromMeta
  const t = (matterType || '').trim()
  if (t in LEGACY_TYPE_TO_SCENE) return LEGACY_TYPE_TO_SCENE[t]
  if (t === 'oa') return 'oa'
  return 'write'
}

export function readSceneFromMeta(metaJson?: string | null): string {
  if (!metaJson) return ''
  try {
    const meta = JSON.parse(metaJson) as Record<string, unknown>
    return meta.scene ? String(meta.scene).trim() : ''
  } catch {
    return ''
  }
}

export function mergeSceneIntoMeta(metaJson: string | undefined | null, scene: string): string {
  let meta: Record<string, unknown> = {}
  if (metaJson) {
    try {
      meta = JSON.parse(metaJson) as Record<string, unknown>
    } catch {
      meta = {}
    }
  }
  if (scene) meta.scene = scene
  else delete meta.scene
  return JSON.stringify(meta)
}

export function matterTypeLabel(matterType?: string | null): string {
  const t = (matterType || '').trim()
  return TYPE_LABELS[t] || t || '—'
}

export function sceneLabel(scene?: string | null): string {
  const s = (scene || '').trim()
  if (!s || s === 'write') return ''
  if (s === 'oa') return '审查答复'
  return SCENE_LABELS[s] || s
}

/** 列表展示：交底撰写 · 专利解读 */
export function matterTypeDisplay(matterType?: string | null, metaJson?: string | null): string {
  const primary = matterTypeLabel(matterType)
  const scene = inferSceneFromMatter(matterType, metaJson)
  const sl = sceneLabel(scene)
  // oa 一级类型本身已表达，不再叠场景
  if ((matterType || '').trim() === 'oa') return primary
  return sl ? `${primary} · ${sl}` : primary
}
