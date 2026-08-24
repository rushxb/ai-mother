export interface UserInfo {
  id: number
  userAccount: string
  userName: string
  userAvatar: string
  userRole: 'user' | 'admin'
  createTime: string
}

export interface LoginParams {
  userAccount: string
  userPassword: string
}
