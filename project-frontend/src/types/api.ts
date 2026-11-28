export interface ApiResponse<T> {
  code: number
  message: string
  data: T | null
}

export interface UserProfileResponse {
  id: number
  username: string
  email: string
}

export interface LoginResponse {
  token: string
  tokenType: 'Bearer'
  expiresIn: number
  user: UserProfileResponse
}

export interface ProductQueryParams {
  brand?: string
  minPrice?: number | string
  maxPrice?: number | string
  minRamGb?: number
  minStorageGb?: number
  status?: 0 | 1
  page?: number
  size?: number
  sortBy?: 'price' | 'createdAt' | 'stock'
  sortDirection?: 'asc' | 'desc'
}

export interface ProductResponse {
  id: number
  brand: string
  model: string
  title: string
  description: string | null
  /** 后端 BigDecimal 默认可能序列化为数字；兼容字符串形式。 */
  price: number | string
  stock: number
  ramGb: number
  storageGb: number
  status: 0 | 1
  createdAt: string
  updatedAt: string
}

export interface AiChatRequest {
  /** 新消息生成一次，网络重试复用。 */
  clientRequestId: string
  conversationId: string
  message: string
}

export interface AiToolCallResponse {
  toolName: string
  success: boolean
  resultSummary: string
  productIds: number[]
  total: number
  queriedAt: string
  /** 原生 Tool Calling 中模型传入的参数 JSON。 */
  arguments?: string
  /** 当前聊天请求内的模型 Tool 调用顺序号。 */
  sequence?: number
  /** callback 收到模型请求后生成的审计证据。 */
  evidence?: 'MODEL_REQUEST' | string
  resultStatus?: 'SUCCESS' | 'PARTIAL_SUCCESS' | 'BUSINESS_FAILURE' | 'SYSTEM_FAILURE' | string
}

export interface AiReferenceResponse {
  productId: number
  title: string | null
  source: string
  facts: string[]
}

export interface AiChatResponse {
  /** AI 本轮执行结果；与购物编排 status 分开。 */
  resultStatus: 'SUCCESS' | 'MODEL_TIMEOUT' | 'MODEL_ERROR' | 'EMPTY_RESPONSE' | 'TOOL_ERROR' | 'GROUNDING_REJECTED'
  conversationId: string
  message: string
  /** 购物编排状态；只有 ORDER_CREATED 才代表服务端已创建订单。 */
  status: 'PREVIEW_CREATED' | 'CONFIRM_REQUIRED' | 'ORDER_CREATED' | null
  orderDraft: OrderDraftResponse | null
  toolCalls: AiToolCallResponse[]
  references: AiReferenceResponse[]
  knowledgeReferences: AiKnowledgeReferenceResponse[]
}

export interface AiKnowledgeReferenceResponse {
  documentId: string
  title: string
  sourceType: string
  content: string
  productId: number | null
}

/** 流式事件名；与后端 SSE 的 event 字段一一对应。 */
export type AiStreamEventName =
  | 'meta'
  | 'text_delta'
  | 'replace'
  | 'final'
  | 'error'

/** meta：流开始，最先到达。 */
export interface AiStreamMeta {
  conversationId: string
  aiTraceId: string
  /** 服务端请求追踪 ID，与响应头 X-Request-Id 一致，排错时用它关联日志。 */
  reqId: string
}

/** text_delta：正文增量，按到达顺序追加。 */
export interface AiStreamTextDelta {
  delta: string
}

/**
 * replace：前端必须丢弃已渲染的全部内容，整体替换为 message。
 * 载荷是完整替换文本，不是增量、也不是「从第 N 个字符起替换」。
 */
export interface AiStreamReplace {
  message: string
  /** 触发中断的校验失败类型，例如 UNVERIFIED_AMOUNT、UNSUPPORTED_PROMOTION。 */
  failureType: string
}

/** error：异常结束；code 与同步接口的 ApiResponse.code 取自同一份映射。 */
export interface AiStreamError {
  code: number
  message: string
  resultStatus: AiChatResponse['resultStatus']
}

export interface CartItemResponse {
  productId: number
  brand: string
  model: string
  title: string
  displayPrice: number | string
  stock: number
  quantity: number
  status: 0 | 1
  displaySubtotal: number | string
}

export interface CartResponse {
  id: number
  items: CartItemResponse[]
  totalQuantity: number
  displayTotalAmount: number | string
}

export interface OrderQueryParams {
  page?: number
  size?: number
}

/** 订单状态：1 待支付 / 2 已支付 / 3 已关闭。 */
export type OrderStatus = 1 | 2 | 3

/** 订单关闭原因：USER_CANCELLED 用户取消 / PAYMENT_TIMEOUT 超时未支付。 */
export type OrderCloseReason = 'USER_CANCELLED' | 'PAYMENT_TIMEOUT'

export interface OrderSummaryResponse {
  id: number
  orderNo: string
  status: OrderStatus
  totalAmount: number | string
  createdAt: string
  /** 支付时限，待支付订单据此展示支付倒计时。 */
  expiresAt: string
  /** 支付完成时间，仅已支付订单有值。 */
  paidAt: string | null
  /** 关闭时间，仅已关闭订单有值。 */
  closedAt: string | null
  /** 关闭原因，仅已关闭订单有值：USER_CANCELLED 或 PAYMENT_TIMEOUT。 */
  closeReason: OrderCloseReason | null
  itemCount: number
}

export interface OrderItemResponse {
  productId: number
  productTitle: string
  brand: string
  model: string
  unitPrice: number | string
  quantity: number
  subtotal: number | string
}

export interface OrderResponse {
  id: number
  orderNo: string
  status: OrderStatus
  totalAmount: number | string
  createdAt: string
  /** 支付时限，待支付订单据此展示支付倒计时。 */
  expiresAt: string
  /** 支付完成时间，仅已支付订单有值。 */
  paidAt: string | null
  /** 关闭时间，仅已关闭订单有值。 */
  closedAt: string | null
  /** 关闭原因，仅已关闭订单有值：USER_CANCELLED 或 PAYMENT_TIMEOUT。 */
  closeReason: OrderCloseReason | null
  items: OrderItemResponse[]
}

export interface OrderPreviewItemResponse {
  productId: number
  productTitle: string
  brand: string
  model: string
  previewUnitPrice: number | string
  quantity: number
  previewSubtotal: number | string
}

export interface OrderDraftResponse {
  draftId: string
  createdAt: string
  expiresAt: string
  status: 'PREVIEWED' | 'CONFIRMED' | 'EXPIRED' | 'STALE' | 'CANCELLED'
  items: OrderPreviewItemResponse[]
  previewTotalAmount: number | string
  source: 'CART'
  version: number
  fingerprint: string
  confirmedOrderId?: number | null
  unavailableReason?: string | null
}

export interface PageResponse<T> {
  records: T[]
  current: number
  size: number
  total: number
  pages: number
}

/** 服务端保存的执行事实。UNKNOWN 不表示操作已回滚。 */
export interface AgentRunResponse {
  runId: string
  clientRequestId: string
  conversationId: string
  userMessage: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'INTERRUPTED'
  errorCode: string | null
  createdAt: string
  finishedAt: string | null
  response: AiChatResponse | null
  steps: Array<{
    sequence: number
    toolName: string
    arguments: string
    status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'
    outcome: string | null
    resultJson: string | null
    resultSource: 'TOOL_EXECUTION' | 'BUSINESS_RECORD' | 'RECOVERY'
    retryCount: number
    recovering: boolean
    canRetry: boolean
    recoveryDescription: string
    resultSummary: string | null
    recoveryAttempts: Array<{ attempt: number; status: string; outcome: string | null; createdAt: string }>
  }>
}
