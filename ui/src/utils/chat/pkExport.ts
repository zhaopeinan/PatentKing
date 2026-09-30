/** 从 pk_export_disclosure 工具参数字符串解析 markdown（参数流式到达时可能尚未完整） */
export function parsePkExportToolArgs(
  args?: string,
): { markdown: string; title: string; diagramMode?: string } | null {
  if (!args || args === '{}') return null
  try {
    const obj = JSON.parse(args) as Record<string, unknown>
    const markdown = typeof obj.markdown === 'string' ? obj.markdown.trim() : ''
    if (markdown.length < 80) return null
    const title = typeof obj.title === 'string' && obj.title.trim()
      ? obj.title.trim()
      : 'disclosure'
    const diagramMode =
      typeof obj.diagram_mode === 'string' && obj.diagram_mode.trim()
        ? obj.diagram_mode.trim()
        : undefined
    return { markdown, title, diagramMode }
  } catch {
    return null
  }
}
