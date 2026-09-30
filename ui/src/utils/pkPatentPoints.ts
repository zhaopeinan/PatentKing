import type { PkPatentPointItem, PkPatentPointsDTO, PkPatentPointsState } from '@/types'
import { parseUIPJson } from '@/utils/chat/uip'

export function parseIdList(raw: unknown): string[] {
  if (Array.isArray(raw)) {
    return raw.map(v => String(v).trim()).filter(Boolean)
  }
  if (typeof raw !== 'string') {
    return []
  }
  const text = raw.trim()
  if (!text) {
    return []
  }
  if (text.startsWith('[')) {
    try {
      return parseIdList(JSON.parse(text))
    } catch {
      /* fall through */
    }
  }
  return text.split(/[,，\s]+/).map(s => s.trim()).filter(Boolean)
}

export function parseInventory(raw: unknown): PkPatentPointItem[] {
  let list: unknown = raw
  if (typeof raw === 'string') {
    const text = raw.trim()
    if (!text) {
      return []
    }
    try {
      list = JSON.parse(text)
    } catch {
      return []
    }
  }
  if (!Array.isArray(list)) {
    return []
  }
  return list.map((row, idx) => {
    const item = (row && typeof row === 'object') ? row as Record<string, unknown> : {}
    const id = String(item.id ?? `P${idx + 1}`)
    return {
      id,
      title: String(item.title ?? id),
      bucket: item.bucket ? String(item.bucket) : 'file',
      summary: item.summary ? String(item.summary) : '',
      priorArtNote: item.priorArtNote ? String(item.priorArtNote) : '',
    }
  })
}

export function parsePatentPointsMeta(metaJson?: string | null): PkPatentPointsState | null {
  if (!metaJson) {
    return null
  }
  try {
    const root = JSON.parse(metaJson) as Record<string, unknown>
    const raw = root.patentPoints
    if (!raw || typeof raw !== 'object') {
      return null
    }
    const points = raw as Record<string, unknown>
    return {
      inventory: parseInventory(points.inventory),
      selectedIds: parseIdList(points.selectedIds),
      tradeSecretIds: parseIdList(points.tradeSecretIds),
      deferredIds: parseIdList(points.deferredIds),
      notes: points.notes ? String(points.notes) : '',
      submittedAt: points.submittedAt ? String(points.submittedAt) : undefined,
      confirmedAt: points.confirmedAt ? String(points.confirmedAt) : undefined,
      gate: points.gate ? String(points.gate) : undefined,
    }
  } catch {
    return null
  }
}

export function dtoFromToolArgs(args: Record<string, unknown>): PkPatentPointsDTO {
  return {
    inventory: parseInventory(args.inventory_json ?? args.inventory),
    selectedIds: parseIdList(args.selected_ids ?? args.selectedIds),
    tradeSecretIds: parseIdList(args.trade_secret_ids ?? args.tradeSecretIds),
    deferredIds: parseIdList(args.deferred_ids ?? args.deferredIds),
    notes: args.notes ? String(args.notes) : undefined,
  }
}

export function dtoFromUipForm(data: Record<string, unknown>, uipCode?: string): PkPatentPointsDTO {
  const selectedIds = parseIdList(data.selected_ids ?? data.selectedIds)
  const tradeSecretIds = parseIdList(data.trade_secret_ids ?? data.tradeSecretIds)
  const deferredIds = parseIdList(data.deferred_ids ?? data.deferredIds)
  return {
    inventory: inventoryFromUipCode(uipCode, selectedIds, tradeSecretIds, deferredIds),
    selectedIds,
    tradeSecretIds,
    deferredIds,
    notes: data.notes ? String(data.notes) : undefined,
  }
}

function inventoryFromUipCode(
  uipCode: string | undefined,
  selectedIds: string[],
  tradeSecretIds: string[],
  deferredIds: string[],
): PkPatentPointItem[] {
  if (!uipCode) {
    return []
  }
  const uip = parseUIPJson(uipCode)
  if (!uip || uip.interaction?.type !== 'form') {
    return []
  }
  const fields = 'fields' in uip.interaction ? uip.interaction.fields : []
  const map = new Map<string, PkPatentPointItem>()
  const absorb = (name: string, fallback: string) => {
    const field = fields.find(f => f.name === name)
    for (const opt of field?.options || []) {
      const id = String(opt.value)
      if (!id || map.has(id)) continue
      map.set(id, {
        id,
        title: titleFromOption(id, String(opt.label ?? id)),
        bucket: fallback,
        summary: opt.description ? String(opt.description) : '',
      })
    }
  }
  absorb('selected_ids', 'file')
  absorb('trade_secret_ids', 'secret')
  absorb('deferred_ids', 'defer')
  for (const item of map.values()) {
    if (tradeSecretIds.includes(item.id)) item.bucket = 'secret'
    else if (deferredIds.includes(item.id)) item.bucket = 'defer'
    else if (selectedIds.includes(item.id)) item.bucket = 'file'
  }
  return [...map.values()]
}

function titleFromOption(id: string, label: string): string {
  const text = label.trim()
  if (text.startsWith(id)) {
    return text.slice(id.length).replace(/^[\s:：.\-]+/, '') || text
  }
  return text || id
}

export function parseChatBindings(metaJson?: string | null): {
  lastSessionId: string
  chatSessions: Array<{ id: string; title?: string; boundAt?: string }>
} {
  if (!metaJson) {
    return { lastSessionId: '', chatSessions: [] }
  }
  try {
    const root = JSON.parse(metaJson) as Record<string, unknown>
    const last = root.lastSessionId == null ? '' : String(root.lastSessionId)
    const raw = root.chatSessions
    const chatSessions = Array.isArray(raw)
      ? raw.map(row => {
          const item = (row && typeof row === 'object') ? row as Record<string, unknown> : {}
          return {
            id: String(item.id ?? ''),
            title: item.title ? String(item.title) : '对话',
            boundAt: item.boundAt ? String(item.boundAt) : '',
          }
        }).filter(row => row.id)
      : []
    return { lastSessionId: last, chatSessions }
  } catch {
    return { lastSessionId: '', chatSessions: [] }
  }
}
