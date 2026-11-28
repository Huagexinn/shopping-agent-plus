import { http } from './http'
import type {
  ApiResponse,
  OrderDraftResponse,
  OrderQueryParams,
  OrderResponse,
  OrderSummaryResponse,
  PageResponse,
} from '@/types/api'

export async function previewOrder(): Promise<ApiResponse<OrderDraftResponse>> {
  const response = await http.post<ApiResponse<OrderDraftResponse>>('/orders/preview', {})
  return response.data
}

export async function fetchOrderDraft(draftId: string): Promise<ApiResponse<OrderDraftResponse>> {
  const response = await http.get<ApiResponse<OrderDraftResponse>>(`/orders/drafts/${encodeURIComponent(draftId)}`)
  return response.data
}

export async function confirmOrder(draftId: string, idempotencyKey: string, fingerprint: string): Promise<ApiResponse<OrderResponse>> {
  const response = await http.post<ApiResponse<OrderResponse>>(`/orders/${draftId}/confirm`, { confirmed: true, fingerprint }, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return response.data
}

/** 分页查询当前登录用户订单。 */
export async function fetchOrders(
  params?: OrderQueryParams,
): Promise<ApiResponse<PageResponse<OrderSummaryResponse>>> {
  const response = await http.get<ApiResponse<PageResponse<OrderSummaryResponse>>>('/orders', { params })
  return response.data
}

/** 查询当前登录用户订单详情。 */
export async function fetchOrderDetail(id: number): Promise<ApiResponse<OrderResponse>> {
  const response = await http.get<ApiResponse<OrderResponse>>(`/orders/${id}`)
  return response.data
}

/** 支付当前登录用户的待支付订单；重复支付返回既有订单。 */
export async function payOrder(id: number): Promise<ApiResponse<OrderResponse>> {
  const response = await http.post<ApiResponse<OrderResponse>>(`/orders/${id}/pay`)
  return response.data
}

/** 取消当前登录用户的待支付订单并归还库存；重复取消返回既有订单。 */
export async function cancelOrder(id: number): Promise<ApiResponse<OrderResponse>> {
  const response = await http.post<ApiResponse<OrderResponse>>(`/orders/${id}/cancel`)
  return response.data
}
