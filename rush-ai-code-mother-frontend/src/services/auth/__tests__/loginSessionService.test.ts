import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { getLoginUser, userLogin } from '@/api/userController.ts'
import { useLoginUserStore } from '@/stores/loginUser'
import { loginAndInitializeSession } from '../loginSessionService'

vi.mock('@/api/userController.ts', () => ({
  getLoginUser: vi.fn(),
  userLogin: vi.fn(),
}))

const mockedGetLoginUser = vi.mocked(getLoginUser)
const mockedUserLogin = vi.mocked(userLogin)

describe('loginAndInitializeSession', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('replaces a previously cached anonymous session with the user returned by login', async () => {
    mockedGetLoginUser.mockResolvedValue({
      data: {
        code: 40100,
        message: '未登录',
      },
    } as Awaited<ReturnType<typeof getLoginUser>>)

    const sessionStore = useLoginUserStore()
    await sessionStore.fetchLoginUser()
    expect(sessionStore.status).toBe('anonymous')
    expect(sessionStore.isAuthenticated).toBe(false)

    const authenticatedUser: API.LoginUserVO = {
      id: 1001,
      userAccount: 'tester',
      userName: '测试用户',
      userRole: 'user',
    }
    mockedUserLogin.mockResolvedValue({
      data: {
        code: 0,
        data: authenticatedUser,
      },
    } as Awaited<ReturnType<typeof userLogin>>)

    const result = await loginAndInitializeSession(
      { userAccount: 'tester', userPassword: 'password123' },
      sessionStore,
    )

    expect(result).toEqual({ success: true, user: authenticatedUser })
    expect(sessionStore.loginUser).toEqual(authenticatedUser)
    expect(sessionStore.status).toBe('authenticated')
    expect(sessionStore.isAuthenticated).toBe(true)
    expect(mockedGetLoginUser).toHaveBeenCalledTimes(1)
  })

  it('does not mutate the current session when the backend rejects login', async () => {
    mockedUserLogin.mockResolvedValue({
      data: {
        code: 40000,
        message: '账号或密码错误',
      },
    } as Awaited<ReturnType<typeof userLogin>>)
    const sessionStore = {
      setLoginUser: vi.fn(),
    }

    const result = await loginAndInitializeSession(
      { userAccount: 'tester', userPassword: 'wrong-password' },
      sessionStore,
    )

    expect(result).toEqual({ success: false, message: '账号或密码错误' })
    expect(sessionStore.setLoginUser).not.toHaveBeenCalled()
  })
})
