import { message } from 'ant-design-vue'
import router from '@/router'
import { useLoginUserStore } from '@/stores/loginUser'

router.beforeEach(async (to) => {
  const loginUserStore = useLoginUserStore()
  await loginUserStore.fetchLoginUser()

  const requiresAuth = Boolean(to.meta.requiresAuth)
  const requiredRole = typeof to.meta.requiredRole === 'string' ? to.meta.requiredRole : undefined

  if ((requiresAuth || requiredRole) && !loginUserStore.isAuthenticated) {
    if (loginUserStore.status === 'error') {
      message.error('登录状态校验失败，请稍后重试')
    } else {
      message.warning('请先登录')
    }
    return {
      path: '/user/login',
      query: { redirect: to.fullPath },
    }
  }

  if (requiredRole && loginUserStore.loginUser.userRole !== requiredRole) {
    message.error('无权限访问该页面')
    return '/'
  }

  return true
})
