import { userLogin } from '@/api/userController.ts'

export interface LoginSessionStore {
  setLoginUser(user: API.LoginUserVO | null): void
}

export type LoginResult =
  | { success: true; user: API.LoginUserVO }
  | { success: false; message: string }

/**
 * Executes the login request and synchronizes the client-side session.
 *
 * The login endpoint already returns the authoritative authenticated user.
 * Applying that response directly avoids reusing a previously cached anonymous
 * session before a protected-route navigation.
 */
export async function loginAndInitializeSession(
  credentials: API.UserLoginRequest,
  sessionStore: LoginSessionStore,
): Promise<LoginResult> {
  const response = await userLogin(credentials)
  const responseBody = response.data
  const authenticatedUser = responseBody.data

  if (responseBody.code !== 0 || !authenticatedUser?.id) {
    return {
      success: false,
      message: responseBody.message || '服务异常',
    }
  }

  sessionStore.setLoginUser(authenticatedUser)

  return {
    success: true,
    user: authenticatedUser,
  }
}
