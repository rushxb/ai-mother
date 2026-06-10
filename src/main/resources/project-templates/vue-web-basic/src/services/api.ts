import { get, post, put, del } from './request'
import type { UserInfo, LoginParams, LoginResult, PageResult } from '@/types'

// Auth APIs
export function login(params: LoginParams) {
  return post<LoginResult>('/auth/login', params)
}

export function logout() {
  return post('/auth/logout')
}

export function getUserInfo() {
  return get<UserInfo>('/auth/userInfo')
}

// User APIs
export function getUserList(params?: { page?: number; pageSize?: number }) {
  return get<PageResult<UserInfo>>('/user/list', { params })
}

export function getUserById(id: number) {
  return get<UserInfo>(`/user/${id}`)
}

export function createUser(data: Partial<UserInfo>) {
  return post<UserInfo>('/user/create', data)
}

export function updateUser(data: Partial<UserInfo>) {
  return put<UserInfo>('/user/update', data)
}

export function deleteUser(id: number) {
  return del(`/user/${id}`)
}

// @AI_INJECT_API
