import type { PkDeliveryDTO } from '@/types'

export const PK_DELIVERY_UIP_ID = 'pk_delivery_format'

export function dtoFromUipForm(data: Record<string, unknown>): PkDeliveryDTO {
  const includeRaw = data.include_pdf
  let includePdf = false
  if (includeRaw === true || includeRaw === 'yes') {
    includePdf = true
  } else if (Array.isArray(includeRaw)) {
    includePdf = includeRaw.map(String).includes('yes')
  }
  return {
    previewAction: data.preview_action ? String(data.preview_action) : undefined,
    adjustNotes: data.adjust_notes ? String(data.adjust_notes) : undefined,
    deliveryFormat: data.delivery_format ? String(data.delivery_format) : undefined,
    diagramMode: data.diagram_mode ? String(data.diagram_mode) : undefined,
    includePdf,
  }
}

export function parseDeliveryMeta(metaJson?: string | null): PkDeliveryDTO | null {
  if (!metaJson) return null
  try {
    const root = JSON.parse(metaJson) as Record<string, unknown>
    const raw = root.delivery
    if (!raw || typeof raw !== 'object') return null
    const d = raw as Record<string, unknown>
    return {
      previewAction: d.previewAction ? String(d.previewAction) : undefined,
      adjustNotes: d.adjustNotes ? String(d.adjustNotes) : undefined,
      deliveryFormat: d.deliveryFormat ? String(d.deliveryFormat) : undefined,
      diagramMode: d.diagramMode ? String(d.diagramMode) : undefined,
      includePdf: d.includePdf === true,
    }
  } catch {
    return null
  }
}

export function diagramModeLabel(mode?: string): string {
  const m = (mode || 'png').toLowerCase()
  if (m === 'auto' || m === 'tokenlab' || m === 'image-api') {
    return 'Tokenlab 自动生成'
  }
  return 'PNG 机器渲染'
}

/** 表单提交后发给 Agent 的结构化说明（禁止再口头询问交付格式） */
export function buildDeliveryAgentMessage(data: Record<string, unknown>): string {
  const dto = dtoFromUipForm(data)
  const lines = [
    '[UIP 提交 pk_delivery_format — 用户已在表单中点击选择，禁止再次询问交付格式或让用户口头回答]',
    `摘要方向=${dto.previewAction === 'adjust' ? '需要调整' : '确认成文'}`,
  ]
  if (dto.adjustNotes) {
    lines.push(`调整说明=${dto.adjustNotes}`)
  }
  lines.push(`交付格式=${dto.deliveryFormat === 'markdown' ? 'markdown' : 'word'}`)
  if (dto.deliveryFormat !== 'markdown') {
    lines.push(`图示方式=${diagramModeLabel(dto.diagramMode)}（diagram_mode=${dto.diagramMode || 'png'}）`)
    if ((dto.diagramMode || 'png') === 'auto') {
      lines.push('auto 模式须确保租户已配置 Tokenlab；未配置时导出应提示管理员到「系统设置 → PatentKing 生图」填写 API Key。')
    }
    lines.push(`需要PDF=${dto.includePdf ? '是（当前平台优先 Word，PDF 可后续）' : '否'}`)
  }
  if (dto.previewAction === 'adjust') {
    lines.push('请根据调整说明修订摘要预览，并再次弹出 pk_delivery_format 表单；禁止写交底正文。')
  } else {
    lines.push(
      '请按以上选择写交底正文；若 delivery_format=word，成文后必须调用 pk_export_disclosure，diagram_mode 与表单一致。',
    )
  }
  return lines.join('\n')
}

export function defaultDiagramMode(metaJson?: string | null): string {
  return parseDeliveryMeta(metaJson)?.diagramMode || 'png'
}
