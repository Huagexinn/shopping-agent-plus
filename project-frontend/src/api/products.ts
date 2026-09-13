import { http } from './http'
import type {
  ApiResponse,
  PageResponse,
  ProductQueryParams,
  ProductResponse,
} from '@/types/api'

/** 分页查询公开上架商品。实际请求前缀由 http.baseURL 统一提供。 */
export async function fetchProducts(
  params?: ProductQueryParams,
): Promise<ApiResponse<PageResponse<ProductResponse>>> {
  const response = await http.get<ApiResponse<PageResponse<ProductResponse>>>('/products', { params })
  return response.data
}

/** 查询指定商品的公开详情。 */
export async function fetchProductDetail(
  id: number,
): Promise<ApiResponse<ProductResponse>> {
  const response = await http.get<ApiResponse<ProductResponse>>(`/products/${id}`)
  return response.data
}
