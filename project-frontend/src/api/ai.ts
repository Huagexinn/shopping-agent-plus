import { http } from './http'
import { getToken } from '@/utils/storage'
import type {
  AiChatRequest,
  AiChatResponse,
  AiStreamError,
  AiStreamMeta,
  AiStreamReplace,
  ApiResponse,
  AgentRunResponse,
} from '@/types/api'

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api'

export async function getAgentRun(clientRequestId: string): Promise<AgentRunResponse> {
  const { data } = await http.get<ApiResponse<AgentRunResponse>>(`/ai/runs/${encodeURIComponent(clientRequestId)}`)
  if (data.code !== 0 || !data.data) throw new Error(data.message || '无法查询执行记录')
  return data.data
}

export async function recoverAgentStep(clientRequestId: string, sequence: number): Promise<AgentRunResponse> {
  const { data } = await http.post<ApiResponse<AgentRunResponse>>(
    `/ai/runs/${encodeURIComponent(clientRequestId)}/steps/${sequence}/recover`, { confirmed: true }, { timeout: 35_000 })
  if (data.code !== 0 || !data.data) throw new Error(data.message || '恢复结果尚未取得，请查询原任务')
  return data.data
}

export async function getAgentRuns(conversationId: string): Promise<AgentRunResponse[]> {
  const { data } = await http.get<ApiResponse<AgentRunResponse[]>>('/ai/runs', { params: { conversationId } })
  if (data.code !== 0 || !data.data) throw new Error(data.message || '无法查询执行记录')
  return data.data
}

/** 提交 AI 聊天与安全购物编排请求。 */
export async function sendAiChat(request: AiChatRequest): Promise<ApiResponse<AiChatResponse>> {
  const response = await http.post<ApiResponse<AiChatResponse>>('/ai/chat', request, {
    // 原生 Tool Calling 后端最多等待 30 秒，给网络和响应封装预留少量余量。
    timeout: 35_000,
  })
  return response.data
}

export interface AiStreamHandlers {
  onMeta?: (payload: AiStreamMeta) => void
  onTextDelta?: (delta: string) => void
  onReplace?: (payload: AiStreamReplace) => void
  /** 正常结束，携带完整响应；其 message 是权威文本。 */
  onFinal?: (response: AiChatResponse) => void
  /** 异常结束；收到后不会再有 final。 */
  onError?: (payload: AiStreamError) => void
}

/**
 * 流式对话客户端。
 *
 * <p>这里刻意不用 axios：axios 无法消费 SSE 流。也不用 EventSource —— 它只支持 GET，
 * 且无法携带 Authorization 头。因此用 fetch + ReadableStream 手写 SSE 解析。
 * 这是与 {@link http} 封装风格的一处例外。</p>
 *
 * <p>只有「连接建立失败」或「流在中途异常断开」才抛出异常；收到 error 事件不算异常，
 * 它由 {@link AiStreamHandlers.onError} 处理，调用方据此决定是否降级。</p>
 */
export async function streamAiChat(
  request: AiChatRequest,
  handlers: AiStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const token = getToken()
  const response = await fetch(`${apiBaseUrl}/ai/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(request),
    signal,
  })

  if (!response.ok) {
    // 参数校验失败、未登录等发生在流开始之前，这里仍是普通 HTTP 错误
    throw new Error(await readErrorMessage(response))
  }
  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let pending = ''
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) {
        break
      }
      pending += decoder.decode(value, { stream: true })
      let boundary = pending.indexOf('\n\n')
      while (boundary >= 0) {
        dispatchEventBlock(pending.slice(0, boundary), handlers)
        pending = pending.slice(boundary + 2)
        boundary = pending.indexOf('\n\n')
      }
    }
    // 流结束时可能残留最后一个没有空行收尾的事件块
    if (pending.trim()) {
      dispatchEventBlock(pending, handlers)
    }
  } finally {
    reader.releaseLock()
  }
}

/** 解析一个 SSE 事件块并分发；只认 event 与 data 两类字段。 */
function dispatchEventBlock(block: string, handlers: AiStreamHandlers): void {
  let eventName = ''
  const dataLines: string[] = []
  for (const line of block.replace(/\r\n/g, '\n').split('\n')) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }
  if (!eventName) {
    return
  }
  const raw = dataLines.join('\n')
  if (!raw) {
    return
  }
  let payload: unknown
  try {
    payload = JSON.parse(raw)
  } catch {
    console.warn('[ai] 忽略无法解析的流式事件', eventName)
    return
  }
  switch (eventName) {
    case 'meta':
      handlers.onMeta?.(payload as AiStreamMeta)
      break
    case 'text_delta':
      handlers.onTextDelta?.((payload as { delta?: string }).delta ?? '')
      break
    case 'replace':
      handlers.onReplace?.(payload as AiStreamReplace)
      break
    case 'final':
      handlers.onFinal?.(payload as AiChatResponse)
      break
    case 'error':
      handlers.onError?.(payload as AiStreamError)
      break
    default:
      console.warn('[ai] 收到未知的流式事件', eventName)
  }
}

/** 从普通 JSON 错误响应里取出中文提示，取不到时退回 HTTP 状态描述。 */
async function readErrorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string }
    if (body?.message) {
      return body.message
    }
  } catch {
    // 非 JSON 响应，退回到状态码描述
  }
  return `请求失败（HTTP ${response.status}）`
}
