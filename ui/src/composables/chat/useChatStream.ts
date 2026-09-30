import { ref } from 'vue'
import { message } from 'ant-design-vue'
import { useAgentClient } from '@/composables/useAgentClient'
import { usePlanTracking } from '@/composables/chat/usePlanTracking'
import { useSubAgentRuns } from '@/composables/chat/useSubAgentRuns'
import { buildToolCallsContent } from '@/utils/chat/format'
import type {ChatMessageVO, RawEvent, ContextUsageEvent, ContextCompressionEvent} from '@/types'
import { useAccountStore } from '@/stores'
import { getRunStatus, stopRun } from '@/api/agui'

let lastIdBig = BigInt(Date.now()) << 12n;
function nextIdBig() {
  return String(lastIdBig++);
}

export function useChatStream(
  agentId: import('vue').Ref<string>,
  agentDetail: import('vue').Ref<any>,
  currentSessionId: import('vue').Ref<string | null>,
  fileIds?: import('vue').Ref<string[]>,
  memoryActive?: import('vue').Ref<boolean>,
  planActive?: import('vue').Ref<boolean>,
  toolProcessActive?: import('vue').Ref<boolean>,
  onMessageSaved?: (chatMsg: ChatMessageVO) => void,
  extraForwarded?: import('vue').Ref<Record<string, unknown>>,
  onToolCompleted?: (tool: {
    id: string
    name: string
    args: string
    result?: string
    elapsed?: number
  }) => void,
) {

  const { userInfo } = useAccountStore()
  const { runs: subAgentRuns, acceptCustomEvent, reset: resetSubAgentRuns } = useSubAgentRuns()

  // 计划追踪
  const {
    currentPlan,
    hasPlan,
    onToolStart: onPlanToolStart,
    onToolArgs: onPlanToolArgs,
    onToolResult: onPlanToolResult,
    resetPlan
  } = usePlanTracking()

  const getForwardedProps = () => ({
    agentId: agentId.value,
    agentCode: agentDetail.value?.agentCode,
    fileIds: fileIds?.value ?? [],
    memoryActive: memoryActive?.value ?? false,
    planActive: planActive?.value ?? false,
    toolProcessActive: toolProcessActive?.value ?? false,
    userInfo: userInfo,
    ...(extraForwarded?.value || {})
  })

  // 流式内容
  const agentHasResult = ref(true)
  const streamingMessageId = ref<string | null>(null)
  const streamingRole = ref<'user' | 'assistant' | 'system' | 'tool' | 'thinking'>('system')
  const streamingContent = ref('')
  const contextUsage = ref<ContextUsageEvent['value'] | null>(null)
  const memoryCompressionActive = ref(false)
  const isStopping = ref(false)
  let stopRequestId = 0
  let compressionNoticeTimer: ReturnType<typeof setTimeout> | null = null

  /** 停止等待后端确认的最长时间（工具如 pk_export_disclosure 可能阻塞较久） */
  const STOP_ACK_TIMEOUT_MS = 20_000

  /** 等待后端确认 Agent 结束；超时后仍释放前端停止态，避免界面长期卡死。 */
  const waitForRunStopped = async (sessionId: string): Promise<boolean> => {
    const deadline = Date.now() + STOP_ACK_TIMEOUT_MS
    while (Date.now() < deadline) {
      try {
        const status = await getRunStatus(sessionId)
        if (!status.running || status.state === 'COMPLETED') return true
      } catch {
        // 短暂网络错误不改变停止状态，下一轮继续查询。
      }
      await new Promise(resolve => setTimeout(resolve, 500))
    }
    return false
  }

  // 工具调用进度
  const toolCallsInProgress = ref<
    Array<{ id: string; name: string; args: string; result?: string; startTime: number; elapsed?: number, needConfirm?: boolean }>
  >([])

  // HITL：逐工具确认决策（toolUseId → 状态），所有项决策完即调 /agui/resume
  const pendingConfirms = ref<Record<string, 'pending' | 'approved' | 'rejected'>>({})

  /**
   * HITL：根据待确认列表重建确认 UI（标记/新建工具项 + 建立逐工具决策态）。两条来源共用：
   * - 实时 TOOL_CONFIRM_REQUIRED 事件：工具项已由 ToolCallStart 建立，只需标记 needConfirm；
   * - 刷新/重进会话（GET /agui/pending 恢复）：toolCallsInProgress 已被清空，须按 input 新建工具项，
   *   否则没有任何工具项承载「允许/禁止」按钮，暂停态卡死无法续点。
   * @param pending 待确认工具 [{toolUseId,name,input}]
   */
  const restorePending = (
    pending: Array<{ toolUseId: string; name: string; input?: Record<string, unknown> }>
  ) => {
    if (!pending || pending.length === 0) return
    const ids = new Set(pending.map(p => p.toolUseId))
    // 已存在的标记 needConfirm
    const arr = toolCallsInProgress.value.map(t => (ids.has(t.id) ? { ...t, needConfirm: true } : t))
    // 缺失的新建（刷新场景）
    const existing = new Set(arr.map(t => t.id))
    pending.forEach(p => {
      if (!existing.has(p.toolUseId)) {
        const args = p.input && Object.keys(p.input).length ? JSON.stringify(p.input) : '{}'
        arr.push({ id: p.toolUseId, name: p.name, args, needConfirm: true, startTime: Date.now() })
      }
    })
    toolCallsInProgress.value = arr
    const next: Record<string, 'pending' | 'approved' | 'rejected'> = { ...pendingConfirms.value }
    pending.forEach(p => { next[p.toolUseId] = 'pending' })
    pendingConfirms.value = next
  }

  // 使用原有的 useAgentClient
  const { messages, isRunning, isReplaying, run, abort, disconnect, reconnect, resume, addUserMessage, client } = useAgentClient({
    handlers: {
      onRunStarted: () => {
        toolCallsInProgress.value = []
        streamingContent.value = ''
        streamingMessageId.value = null
        contextUsage.value = null
        memoryCompressionActive.value = false
        if (compressionNoticeTimer) {
          clearTimeout(compressionNoticeTimer)
          compressionNoticeTimer = null
        }
        resetSubAgentRuns()
      },
      onTextMessageStart: (e) => {
        streamingRole.value = 'assistant'
        streamingContent.value = ''
        streamingMessageId.value = e.messageId
      },
      onTextMessageContent: (_e, currentText) => {
        agentHasResult.value = true
        streamingContent.value = currentText
      },
      onTextMessageEnd: (_e, finalText) => {
        const sid = currentSessionId.value
        if (sid && finalText) {
          // 纯文本保存，不再与推理打包
          onMessageSaved?.({
            id: streamingMessageId.value,
            sessionId: sid,
            role: streamingRole.value,  // 这里必须使用 streamingRole.value，不能写死 assistant
            content: finalText,
            parentId: '',
            path: '',
            depth: 0,
            createdAt: ''
          } as ChatMessageVO)
        }
        // 无论是否回放，都清除流式状态
        streamingMessageId.value = null
        streamingContent.value = ''
        streamingRole.value = 'system'
      },
      onReasoningMessageStart: (e) => {
        streamingRole.value = 'thinking'
        streamingContent.value = ''
        streamingMessageId.value = e.messageId
      },
      onReasoningMessageContent: (_e, currentText) => {
        streamingContent.value = currentText
      },
      onReasoningMessageEnd: () => {
        const sid = currentSessionId.value
        if (sid && streamingContent.value) {
          // 推理结束时立即保存为独立消息
          onMessageSaved?.({
            id: streamingMessageId.value,
            sessionId: sid,
            role: streamingRole.value, // 这里必须使用 streamingRole.value，不能写死 thinking
            content: streamingContent.value,
            parentId: '',
            path: '',
            depth: 0,
            createdAt: ''
          } as ChatMessageVO)
          // 保存完成后清除推理状态，利用 displayMessages 去重避免闪烁
          streamingMessageId.value = null
          streamingContent.value = ''
          streamingRole.value = 'system'
        }
      },
      onToolCallStart: (e) => {
        // 计划追踪：记录工具调用名称
        onPlanToolStart(e.toolCallId, e.toolCallName)

        agentHasResult.value = true
        toolCallsInProgress.value = [
          ...toolCallsInProgress.value,
          { id: e.toolCallId, name: e.toolCallName, args: '', startTime: Date.now() }
        ]

        const sid = currentSessionId.value
        if (sid && streamingContent.value) {
          // 推理结束时保存为独立消息
          onMessageSaved?.({
            id: streamingMessageId.value,
            sessionId: sid,
            role: streamingRole.value,
            content: streamingContent.value,
            parentId: '',
            path: '',
            depth: 0,
            createdAt: ''
          } as ChatMessageVO)
          // 保存完成后清除推理状态，利用 displayMessages 去重避免闪烁
          streamingMessageId.value = null
          streamingContent.value = ''
          streamingRole.value = 'system'
        }
      },
      onToolCallArgs: (_e, partialArgs) => {
        // 计划追踪：累积工具参数
        onPlanToolArgs(_e.toolCallId, partialArgs)

        const arr = [...toolCallsInProgress.value]
        const last = arr[arr.length - 1]
        if (last) last.args = partialArgs
        toolCallsInProgress.value = arr
      },
      onToolCallResult: (e) => {
        // 计划追踪：处理工具结果
        onPlanToolResult(e.toolCallId)

        const elapsed = Date.now() - (toolCallsInProgress.value.find(t => t.id === e.toolCallId)?.startTime ?? Date.now())
        toolCallsInProgress.value = toolCallsInProgress.value.map((t) =>
          t.id === e.toolCallId ? { ...t, result: e.content, elapsed } : t
        )
        const completedTool = toolCallsInProgress.value.find((t) => t.id === e.toolCallId)
        if (completedTool) {
          onToolCompleted?.(completedTool)
        }

        try {
          const sid = currentSessionId.value
          const matterBound = Boolean(
            (extraForwarded?.value?.params as Record<string, unknown> | undefined)?.matterId
          )
          const saveHistory = (toolProcessActive?.value ?? true) || matterBound
          if (sid && saveHistory && completedTool) {
            const contentToSave = buildToolCallsContent([completedTool])
            if (contentToSave) {
              onMessageSaved?.({
                id: nextIdBig(),
                sessionId: sid,
                role: 'tool',
                content: contentToSave,
                parentId: '',
                path: '',
                depth: 0,
                createdAt: ''
              } as ChatMessageVO)
            }
          }
        } finally {
          // 移除已完成的工具调用，保留仍在进行中的
          toolCallsInProgress.value = toolCallsInProgress.value.filter((t) => t.id !== e.toolCallId)
        }
      },
      onRunFinished: (_e) => {
        agentHasResult.value = true
        // 不再全标记 needConfirm（旧 Bug1/MCP 假象根源）；
        // 确认改由 onCustom 的 TOOL_CONFIRM_REQUIRED 事件精确驱动
      },
      onRaw: (event) => {
        const e = event as RawEvent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const rawEvent: any = e.rawEvent
        if(rawEvent.error) {
          streamingMessageId.value = new Date().getTime() + '' + Math.floor(Math.random() * 90000) + 10000
          streamingContent.value = rawEvent.error
          const sid = currentSessionId.value
          if (sid) {
            onMessageSaved?.({
              id: streamingMessageId.value,
              sessionId: sid,
              role: 'error',
              content: rawEvent.error,
              parentId: '',
              path: '',
              depth: 0,
              createdAt: ''
            } as ChatMessageVO)
            // 保存完成后清除推理状态，利用 displayMessages 去重避免闪烁
            streamingMessageId.value = null
            streamingContent.value = ''
            streamingRole.value = 'system'
          }
        }
     },
      onCustom: (event) => {
        if (event.name === 'CONTEXT_USAGE') {
          contextUsage.value = (event as ContextUsageEvent).value
          return
        }
        if (event.name === 'CONTEXT_COMPRESSION') {
          const compressionEvent = event as ContextCompressionEvent
          contextUsage.value = {
            ...contextUsage.value,
            ...compressionEvent.value,
            ratio: compressionEvent.value.compressionPressure
          } as ContextUsageEvent['value']
          if (compressionNoticeTimer) {
            clearTimeout(compressionNoticeTimer)
            compressionNoticeTimer = null
          }
          memoryCompressionActive.value = compressionEvent.value.status === 'STARTED'
          if (compressionEvent.value.status !== 'STARTED') {
            compressionNoticeTimer = setTimeout(() => {
              memoryCompressionActive.value = false
              compressionNoticeTimer = null
            }, 800)
          }
          return
        }
        // Keep sub-agent CUSTOM events out of the normal text/tool message buffers.
        if (acceptCustomEvent(event)) {
          const trace = event.value as { eventType?: string; invocationId?: string }
          if (trace.eventType === 'STARTED' && trace.invocationId) {
            // The parent tool call is represented by the dedicated card, not a second generic row.
            toolCallsInProgress.value = toolCallsInProgress.value.filter((tool) => tool.id !== trace.invocationId)
            onPlanToolResult(trace.invocationId)
          }
          return
        }
        // HITL：收到 TOOL_CONFIRM_REQUIRED 时，精确标记需确认的工具（不再全标记）
        if (event.name === 'TOOL_CONFIRM_REQUIRED') {
          const pending = (((event.value as any)?.pending) ?? []) as Array<{ toolUseId: string; name: string; input?: Record<string, unknown> }>
          restorePending(pending)
        }
      }
    }
  })

  /**
   * HITL：记录单个工具的确认决策（替代旧的「前端代执行/塞文本 + run 重开一轮」）。
   * 所有待确认工具都决策后，调用 /agui/resume 由后端从暂停点续跑。
   * @param toolUseId 工具调用 id（= TOOL_CONFIRM_REQUIRED 的 toolUseId）
   * @param approved true=允许，false=拒绝
   */
  const decideConfirm = (toolUseId: string, approved: boolean) => {
    if (pendingConfirms.value[toolUseId] === undefined) return
    pendingConfirms.value = {
      ...pendingConfirms.value,
      [toolUseId]: approved ? 'approved' : 'rejected'
    }
    // 该工具按钮收起（已决策）
    toolCallsInProgress.value = toolCallsInProgress.value.map(t =>
      t.id === toolUseId ? { ...t, needConfirm: false } : t
    )
    // 所有待确认工具都已决策 → 提交 resume
    const states = Object.values(pendingConfirms.value)
    if (states.length > 0 && states.every(s => s !== 'pending')) {
      void submitResume()
    }
  }

  /** 汇总逐工具决策并调用后端 resume，续接 SSE 流。 */
  const submitResume = async () => {
    const sid = currentSessionId.value
    if (!sid) return
    const decisions = Object.entries(pendingConfirms.value).map(([toolUseId, s]) => {
      const t = toolCallsInProgress.value.find(x => x.id === toolUseId)
      return { toolUseId, name: t?.name ?? '', approved: s === 'approved' }
    })
    pendingConfirms.value = {}
    agentHasResult.value = false
    await resume(sid, decisions, memoryActive?.value ?? false)
  }

  // 中止运行
  const abortRun = async () => {
    if (isStopping.value || !isRunning.value) return
    const sid = currentSessionId.value
    isStopping.value = true
    const requestId = ++stopRequestId
    let stoppedConfirmed = !sid
    try {
      // 先通知后端进入 STOPPING，再断开当前浏览器 SSE 连接。
      if (sid) {
        try { await stopRun(sid) } catch { /* 状态轮询仍会继续确认后端结果 */ }
      }
      await abort()
      agentHasResult.value = true

      // 重置计划状态
      resetPlan()

      if (sid) {
        // 保存所有进行中的工具调用消息（按顺序逐个保存）
        if (toolCallsInProgress.value.length > 0) {
          for (const tool of toolCallsInProgress.value) {
            const contentToSave = buildToolCallsContent([tool])
            if (contentToSave) {
              onMessageSaved?.({
                id: nextIdBig(),
                sessionId: sid,
                role: 'tool',
                content: contentToSave,
                parentId: '',
                path: '',
                depth: 0,
                createdAt: ''
              } as ChatMessageVO)
            }
          }
        }
        // 保存 AI 回复消息
        else if (streamingContent.value) {
          onMessageSaved?.({
            id: streamingMessageId.value,
            sessionId: sid,
            role: streamingRole.value,
            content: streamingContent.value,
            parentId: '',
            path: '',
            depth: 0,
            createdAt: ''
          } as ChatMessageVO)
        }
      }

      // 只有后端确认 Agent 真正结束后，才清理思考、工具和压缩临时状态。
      stoppedConfirmed = sid ? await waitForRunStopped(sid) : true
      if (!stoppedConfirmed) {
        message.warning(
          '已停止对话；若 Word 仍在后台渲染，可使用工具卡片「直接导出」或到案件页重试',
          6,
        )
      }
    } finally {
      // 会话切换后旧停止请求的轮询不能清理新会话的界面状态。
      if (requestId !== stopRequestId || currentSessionId.value !== sid) return
      toolCallsInProgress.value = []
      pendingConfirms.value = {}
      resetSubAgentRuns()
      streamingMessageId.value = null
      streamingContent.value = ''
      streamingRole.value = 'system'
      memoryCompressionActive.value = false
      if (compressionNoticeTimer) {
        clearTimeout(compressionNoticeTimer)
        compressionNoticeTimer = null
      }
      isStopping.value = false
    }
  }

  // 发送消息（可选传入 fileIds 覆盖，用于发送时已清空输入框的场景）
  const sendMessage = async (
    inputText: string,
    messagesList: ChatMessageVO[],
    overrideFileIds?: string[]
  ) => {
    const effectiveFileIds = overrideFileIds ?? fileIds?.value ?? []
    if (!agentId.value) return
    if (!inputText.trim() && !effectiveFileIds.length) return
    if (isRunning.value || isStopping.value) return
    if (!agentDetail.value?.agentCode) {
      message.error('智能体信息未加载完成，请稍后再试')
      return
    }

    // 构建 client 需要的消息格式
    client.messages = messagesList
      .filter((m) => !['system', 'tool', 'subagent'].includes(m.role))
      .map((m) => ({
        id: String(m.id),
        role: m.role as any,
        content: (m.content || '') as string
      }))

    const forwardedProps = getForwardedProps()
    if (overrideFileIds !== undefined) {
      forwardedProps.fileIds = overrideFileIds
    }

    agentHasResult.value = false
    await run({
      threadId: currentSessionId.value || undefined,
      runId: `run_${Date.now()}_${Math.random().toString(36).slice(2, 11)}`,
      forwardedProps
    })
  }

  /**
   * 重置所有流式状态，用于会话切换时清理旧 session 残留。
   */
  function resetStreamingState() {
    stopRequestId++
    isStopping.value = false
    streamingMessageId.value = null
    streamingContent.value = ''
    streamingRole.value = 'system'
    toolCallsInProgress.value = []
    resetSubAgentRuns()
    agentHasResult.value = true
    currentPlan.value = null
    contextUsage.value = null
    memoryCompressionActive.value = false
    if (compressionNoticeTimer) {
      clearTimeout(compressionNoticeTimer)
      compressionNoticeTimer = null
    }
  }

  return {
    agentHasResult,
    streamingContent,
    streamingMessageId,
    streamingRole,
    toolCallsInProgress,
    subAgentRuns,
    isRunning,
    isStopping,
    isReplaying,
    currentPlan,
    contextUsage,
    memoryCompressionActive,
    hasPlan,
    abortRun,
    sendMessage,
    decideConfirm,
    pendingConfirms,
    restorePending,
    reconnect,
    disconnect,
    resetStreamingState,
    client, // 如果需要暴露
  }
}
