/**
 * Mermaid 预处理与语法修复。
 *
 * LLM 常写出 A[初始提示π(0)]，括号会被解析成圆角节点语法。
 * 渲染前最多准备 3 套候选：原文、给标签加引号、再做符号替换。
 */

const GREEK: Array<[RegExp, string]> = [
  [/π/g, 'pi'],
  [/θ/g, 'theta'],
  [/α/g, 'alpha'],
  [/β/g, 'beta'],
  [/γ/g, 'gamma'],
  [/Δ/g, 'Delta'],
  [/δ/g, 'delta'],
  [/λ/g, 'lambda'],
  [/μ/g, 'mu'],
  [/σ/g, 'sigma'],
  [/Σ/g, 'Sigma'],
  [/φ/g, 'phi'],
  [/ψ/g, 'psi'],
  [/ω/g, 'omega'],
  [/Ω/g, 'Omega'],
]

export const MERMAID_MAX_RENDER_ATTEMPTS = 4

/** 用户点击「重新生成」时传给 LLM 的上下文 */
export interface MermaidRetryPayload {
  /** 当前失败的 mermaid 源码 */
  code: string
  /** mermaid.parse / render 的报错原文 */
  error: string
  /** 前端自动修复已尝试过的候选（避免 LLM 重复同样错误） */
  autoFixAttempts?: string[]
  /** 来自助手消息、描述该图用途的上下文 */
  messageContext?: string
}

export function extractMermaidContext(messageContent: string, failedCode: string): string {
  if (!messageContent?.trim()) return ''
  const needle = preprocessMermaidCode(failedCode).slice(0, 60).trim()
  const sections: string[] = []

  const fenceRe = /```mermaid\s*\n([\s\S]*?)```/gi
  let match: RegExpExecArray | null
  let blockStart = -1
  while ((match = fenceRe.exec(messageContent)) !== null) {
    const body = preprocessMermaidCode(match[1])
    if (!needle || body.includes(needle) || needle.includes(body.slice(0, 60))) {
      blockStart = match.index
      break
    }
  }

  if (blockStart >= 0) {
    const before = messageContent.slice(Math.max(0, blockStart - 1000), blockStart)
    const headings = before.match(/#{1,4}\s+[^\n]+/g) ?? []
    const captions = before.match(/(?:图\s*\d+|流程图|结构图|附图|方法流程)[^\n]*/gi) ?? []
    const hints = [...headings.slice(-2), ...captions.slice(-2)]
    if (hints.length) {
      sections.push(`相关章节：${hints.join('；')}`)
    }
    const tail = before.replace(/```[\s\S]*?```/g, '').trim()
    if (tail) {
      sections.push(tail.slice(-500))
    }
  } else {
    const stripped = messageContent
      .replace(/```mermaid[\s\S]*?```/gi, '\n[其他 mermaid 图已省略]\n')
      .trim()
    if (stripped) {
      sections.push(stripped.slice(-800))
    }
  }

  return sections.join('\n\n').trim()
}

export function buildMermaidRetryUserMessage(payload: MermaidRetryPayload): string {
  const code = preprocessMermaidCode(payload.code)
  const lines = [
    '上一条消息中的 Mermaid 图语法无效，请根据下方**报错**、**上下文**和**失败源码**修正后重新输出。',
    '',
    '## 修复要求',
    '- 节点文字若含括号、公式、希腊字母、逗号、等号、星号或标点，必须用双引号包裹整段，例如：`A["初始提示 pi(0)"]` 或 `D1["COUNT(\'col\')→COUNT(\'*\')"]`',
    '- 标签内部禁止嵌套未转义的双引号；需要引号时用单引号，例如 COUNT("col") 应写为 COUNT(\'col\')',
    '- 保留 graph/flowchart 方向、节点 ID 与连线关系，不要改业务含义',
    '- **只输出一个** ```mermaid 代码块，不要解释',
    '',
  ]

  if (payload.messageContext?.trim()) {
    lines.push('## 图的用途 / 原文上下文', payload.messageContext.trim(), '')
  }

  lines.push(
    '## 解析报错（必须消除此错误）',
    '```text',
    payload.error.trim() || '未知解析错误',
    '```',
    '',
  )

  if (payload.autoFixAttempts?.length) {
    lines.push('## 前端已自动尝试但仍失败的版本（请勿重复同样写法）')
    payload.autoFixAttempts.forEach((attempt, index) => {
      lines.push(`### 自动尝试 ${index + 1}`, '```mermaid', attempt, '```', '')
    })
  }

  lines.push('## 当前失败的源码', '```mermaid', code, '```')
  return lines.join('\n')
}

export function decodeMermaidHtmlEntities(str: string): string {
  return str
    .replace(/&gt;/g, '>')
    .replace(/&lt;/g, '<')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&nbsp;/g, ' ')
}

export function preprocessMermaidCode(code: string): string {
  let processed = decodeMermaidHtmlEntities(code).trim()
  processed = processed.replace(/^```mermaid\s*\n?/i, '').replace(/\n?```$/, '')
  return processed.trim()
}

function escapeLabel(inner: string): string {
  return inner.replace(/"/g, '#quot;')
}

function alreadyQuoted(inner: string): boolean {
  const t = inner.trim()
  return (t.startsWith('"') && t.endsWith('"')) || (t.startsWith("'") && t.endsWith("'"))
}

/**
 * 给 flowchart 节点标签加双引号，避免括号/公式打断解析。
 * A[初始提示π(0)] → A["初始提示π(0)"]
 */
export function quoteMermaidNodeLabels(code: string): string {
  const quoteRect = (_m: string, id: string, inner: string) => {
    if (alreadyQuoted(inner)) return `${id}[${inner}]`
    return `${id}["${escapeLabel(inner)}"]`
  }
  const quoteRound = (_m: string, id: string, inner: string) => {
    if (alreadyQuoted(inner)) return `${id}(${inner})`
    return `${id}("${escapeLabel(inner)}")`
  }
  const quoteDiamond = (_m: string, id: string, inner: string) => {
    if (alreadyQuoted(inner)) return `${id}{${inner}}`
    return `${id}{"${escapeLabel(inner)}"}`
  }

  const skip = /^(graph|flowchart|sequenceDiagram|classDiagram|stateDiagram|erDiagram|gantt|pie|gitGraph|mindmap|timeline|journey|subgraph|end|style|classDef|class |click |linkStyle|direction )\b/i

  return code
    .split('\n')
    .map((line) => {
      const trimmed = line.trim()
      if (!trimmed || trimmed.startsWith('%%') || skip.test(trimmed)) {
        return line
      }
      return line
        .replace(/(\b[A-Za-z_][\w-]*)\[(?!\s*")([^\]\n]+)\]/g, quoteRect)
        .replace(/(\b[A-Za-z_][\w-]*)\((?!\s*")([^)\n]+)\)/g, quoteRound)
        .replace(/(\b[A-Za-z_][\w-]*)\{(?!\s*")([^{}\n]+)\}/g, quoteDiamond)
    })
    .join('\n')
}

export function fixNestedQuotesInQuotedLabels(code: string): string {
  return code
    .split('\n')
    .map((line) =>
      line.replace(
        /(\b[A-Za-z_][\w-]*)\["(.*)"\]/g,
        (_m, id: string, inner: string) => {
          if (!inner.includes('"')) return _m
          return `${id}["${inner.replace(/"/g, "'")}"]`
        },
      ),
    )
    .join('\n')
}

export function sanitizeMermaidAggressive(code: string): string {
  let next = quoteMermaidNodeLabels(code)
  next = fixNestedQuotesInQuotedLabels(next)
  // 标签内嵌套双引号会破坏解析，改为单引号
  next = next.replace(
    /(\b[A-Za-z_][\w-]*)\["([^"\]]*)"\]/g,
    (_m, id: string, inner: string) => `${id}["${escapeLabel(inner.replace(/"/g, "'"))}"]`,
  )
  for (const [re, to] of GREEK) {
    next = next.replace(re, to)
  }
  next = next
    .replace(/（/g, '(')
    .replace(/）/g, ')')
    .replace(/【/g, '[')
    .replace(/】/g, ']')
  return next
}

/** 最多 4 套互不相同的渲染候选。 */
export function buildMermaidRenderAttempts(raw: string): string[] {
  const base = preprocessMermaidCode(raw)
  if (!base) return []
  const quoted = quoteMermaidNodeLabels(base)
  const nestedFixed = fixNestedQuotesInQuotedLabels(quoted)
  const aggressive = sanitizeMermaidAggressive(base)
  const unique: string[] = []
  for (const item of [base, quoted, nestedFixed, aggressive]) {
    if (item && !unique.includes(item)) unique.push(item)
  }
  return unique.slice(0, MERMAID_MAX_RENDER_ATTEMPTS)
}
