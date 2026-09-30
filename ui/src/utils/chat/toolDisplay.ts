/** 工具展示名与执行阶段说明（对话页工具卡片） */

export interface ToolDisplayMeta {
  label: string
  steps: string[]
  runningHint?: string
}

const TOOL_META: Record<string, ToolDisplayMeta> = {
  pk_export_disclosure: {
    label: '交底导出 Word',
    steps: ['解析 Markdown 正文', '渲染 mermaid 图示为 PNG', '生成 Word 文档', '登记案件产物'],
    runningHint: '机器渲染图示并生成 Word，通常需要 30–120 秒（图多时可更久）。可点击「终止」停止助手，或「直接导出」绕过助手在后台完成。',
  },
  pk_confirm_patent_points: {
    label: '专利点确认闸门',
    steps: ['校验资产清单', '等待您确认拟申请点', '写入案件元数据'],
    runningHint: '请在上方表单勾选专利点后点击「允许」。',
  },
  pk_align_disclosure: {
    label: '代理稿对齐检查',
    steps: ['加载专利代理金标准', '校验章节与固定句式', '输出对齐清单'],
    runningHint: '正在与专利代理范本比对格式、措辞与规范…',
  },
  pk_prior_art_search: {
    label: '国知局专利查新',
    steps: ['连接国知局公布公告站', 'Playwright 检索相似中国专利', '整理公开号与摘要'],
    runningHint: '正在检索国知局公布公告站，通常需要 10–30 秒…',
  },
}

export function getToolDisplayMeta(name: string): ToolDisplayMeta {
  return TOOL_META[name] ?? {
    label: name,
    steps: ['准备参数', '执行工具', '返回结果'],
  }
}

export function formatToolResult(name: string, raw?: string): string {
  if (!raw) return ''
  try {
    const obj = JSON.parse(raw) as Record<string, unknown>
    if (name === 'pk_export_disclosure') {
      const lines: string[] = []
      if (obj.ok === true || obj.ok === 'true') {
        lines.push('状态：成功')
      } else if (obj.ok === false || obj.ok === 'false') {
        lines.push('状态：失败')
      }
      if (obj.filename) lines.push(`文件：${obj.filename}`)
      if (obj.export_id || obj.exportId) lines.push(`导出 ID：${obj.export_id ?? obj.exportId}`)
      if (obj.figure_count != null) lines.push(`图示：${obj.figure_count} 张`)
      if (obj.failed_count != null && Number(obj.failed_count) > 0) {
        lines.push(`图示失败：${obj.failed_count} 张（Word 中保留 mermaid 源码）`)
      }
      if (obj.message) lines.push(`说明：${obj.message}`)
      if (obj.hint) lines.push(String(obj.hint))
      if (lines.length) return lines.join('\n')
    }
    if (name === 'pk_confirm_patent_points' && obj.gate) {
      return `闸门：${obj.gate}${obj.message ? `\n${obj.message}` : ''}`
    }
    if (name === 'pk_align_disclosure') {
      const lines: string[] = []
      if (obj.ok === true) lines.push('状态：已与代理金标准对齐')
      else if (obj.ok === false) lines.push('状态：未对齐，须修订')
      if (obj.gate) lines.push(`闸门：${obj.gate}`)
      const summary = obj.summary as Record<string, unknown> | undefined
      if (summary) {
        lines.push(`检查：${summary.pass ?? 0} 通过 / ${summary.fail ?? 0} 必改 / ${summary.warn ?? 0} 建议`)
      }
      if (obj.message) lines.push(String(obj.message))
      if (Array.isArray(obj.must_fix) && obj.must_fix.length) {
        lines.push('必改项：')
        for (const item of obj.must_fix.slice(0, 5)) lines.push(`· ${item}`)
      }
      if (lines.length) return lines.join('\n')
    }
    if (name === 'pk_prior_art_search') {
      const lines: string[] = []
      if (obj.source) lines.push(`来源：${obj.source}`)
      if (obj.degrade) lines.push(`降级：${obj.degrade}`)
      if (obj.message) lines.push(String(obj.message))
      if (obj.hits && Array.isArray(obj.hits)) {
        lines.push(`命中 ${obj.hits.length} 条`)
        const first = obj.hits[0] as Record<string, unknown> | undefined
        if (first?.pub_number) lines.push(`示例：${first.pub_number}`)
      }
      if (lines.length) return lines.join('\n')
    }
    return JSON.stringify(obj, null, 2)
  } catch {
    return raw
  }
}

export function summarizeToolArgs(name: string, args?: string): string {
  if (!args || args === '{}') return ''
  try {
    const obj = JSON.parse(args) as Record<string, unknown>
    if (name === 'pk_export_disclosure') {
      const title = obj.title ? String(obj.title) : ''
      const mode = obj.diagram_mode ? String(obj.diagram_mode) : 'png'
      const modeLabel =
        mode === 'auto' || mode === 'tokenlab' || mode === 'image-api'
          ? 'Tokenlab 自动生成'
          : 'PNG 机器渲染'
      const mdLen = obj.markdown ? String(obj.markdown).length : 0
      return [`标题：${title || '（默认）'}`, `图示：${modeLabel}`, `正文：约 ${mdLen} 字`].join('\n')
    }
    if (name === 'pk_prior_art_search') {
      return `关键词：${obj.query ?? ''}`
    }
    if (name === 'pk_confirm_patent_points') {
      return `拟申请：${obj.selected_ids ?? ''}`
    }
    if (name === 'pk_align_disclosure') {
      const mdLen = obj.markdown ? String(obj.markdown).length : 0
      return `正文：约 ${mdLen} 字`
    }
    return JSON.stringify(obj, null, 2)
  } catch {
    return args
  }
}
