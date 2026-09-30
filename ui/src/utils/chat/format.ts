/**
 * 尝试将字符串格式化为 JSON（美化），失败则返回原文
 */
export function formatToolDisplay(text: string): string {
  if (!text) return text
  const trimmed = text.trim()
  if (!trimmed) return text
  if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
    try {
      const parsed = JSON.parse(trimmed)
      return JSON.stringify(parsed, null, 2)
    } catch {
      return text
    }
  }
  return text
}

/**
 * 构建工具调用的 JSON 内容（用于保存到消息中）
 */
export function buildToolCallsContent(
  toolCalls: Array<{ id: string; name: string; args: string; result?: string; elapsed?: number }>
): string {
  if (toolCalls.length === 0) return ''

  const t = toolCalls[0]!
  const toolContent: Record<string, unknown> = {
    name: t.name,
    totalTimes: t.elapsed ?? 0,
    args: t.args ?? '',
    result: t.result ?? ''
  }

  return JSON.stringify(toolContent)
}

const FILES_PREFIX_SEP = '@==##::::##==@'

/**
 * 根据用户输入生成会话标题（截取前50字符）
 * 支持附件前缀 JSON + 纯附件（用文件名）场景，避免一直停在「新对话」。
 */
export function formatSessionTitle(input: string | null): string {
  let t = (input || '').trim()
  if (!t) return '新对话'

  if (t.includes(FILES_PREFIX_SEP)) {
    const idx = t.indexOf(FILES_PREFIX_SEP)
    const prefix = t.slice(0, idx)
    const rest = t.slice(idx + FILES_PREFIX_SEP.length).trim()
    let fileNames: string[] = []
    try {
      const parsed = JSON.parse(prefix) as { files?: Array<{ name?: string; fileName?: string }> }
      fileNames = (parsed.files || [])
        .map(f => (f.name || f.fileName || '').trim())
        .filter(Boolean)
    } catch {
      /* ignore */
    }
    t = rest || (fileNames.length ? fileNames.join('、') : '')
  }

  t = t.replace(/<\/?(?:workspace-file|agent-tool|agent-skill)[^>]*>/gi, ' ')
  t = t.replace(/\s+/g, ' ').trim()
  if (!t) return '新对话'

  return t.length > 50 ? t.slice(0, 50) + '...' : t
}
