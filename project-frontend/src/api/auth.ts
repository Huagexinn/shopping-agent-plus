import { http } from './http'
import type { ApiResponse, LoginResponse, UserProfileResponse } from '@/types/api'

/** 使用用户名或邮箱登录并获取 Bearer JWT。 */
export async function login(identifier: string, password: string): Promise<ApiResponse<LoginResponse>> {
  const response = await http.post<ApiResponse<LoginResponse>>('/auth/login', { identifier, password })
  return response.data
}

export async function register(
  username: string,
  email: string,
  password: string,
): Promise<ApiResponse<UserProfileResponse>> {
  const response = await http.post<ApiResponse<UserProfileResponse>>('/auth/register', {
    username,
    email,
    password,
  })
  return response.data
}

export async function fetchCurrentUser(): Promise<ApiResponse<UserProfileResponse>> {
  const response = await http.get<ApiResponse<UserProfileResponse>>('/auth/me')
  return response.data
}
