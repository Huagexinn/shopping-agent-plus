import { http } from './http'
import type { ApiResponse, CartResponse } from '@/types/api'

/** 查询当前登录用户购物车。 */
export async function fetchCart(): Promise<ApiResponse<CartResponse>> {
  const response = await http.get<ApiResponse<CartResponse>>('/cart')
  return response.data
}

/** 添加商品到当前登录用户购物车。 */
export async function addCartItem(productId: number, quantity: number): Promise<ApiResponse<CartResponse>> {
  const response = await http.post<ApiResponse<CartResponse>>('/cart/items', { productId, quantity })
  return response.data
}

/** 修改当前登录用户购物车中的商品数量。 */
export async function updateCartItem(productId: number, quantity: number): Promise<ApiResponse<CartResponse>> {
  const response = await http.put<ApiResponse<CartResponse>>(`/cart/items/${productId}`, { quantity })
  return response.data
}

/** 删除当前登录用户购物车中的单项商品。 */
export async function removeCartItem(productId: number): Promise<ApiResponse<CartResponse>> {
  const response = await http.delete<ApiResponse<CartResponse>>(`/cart/items/${productId}`)
  return response.data
}

/** 清空当前登录用户购物车。 */
export async function clearCart(): Promise<ApiResponse<CartResponse>> {
  const response = await http.delete<ApiResponse<CartResponse>>('/cart')
  return response.data
}
