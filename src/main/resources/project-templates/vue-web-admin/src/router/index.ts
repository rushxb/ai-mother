import { createRouter, createWebHistory } from 'vue-router'
import { createRoutes } from './routeFactory'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: createRoutes()
})

// Navigation guard
router.beforeEach((to, _from, next) => {
  const userStore = useUserStore()
  
  // Set page title
  const title = to.meta.title as string
  if (title) {
    document.title = `${title} - ${import.meta.env.VITE_APP_TITLE || 'Admin'}`
  }
  
  // Check auth
  if (to.meta.requiresAuth !== false && !userStore.isLoggedIn) {
    next({ name: 'login', query: { redirect: to.fullPath } })
    return
  }
  
  // Check roles
  const roles = to.meta.roles as string[] | undefined
  if (roles && roles.length > 0 && !roles.includes(userStore.userInfo?.userRole || '')) {
    next({ name: '403' })
    return
  }
  
  next()
})

// @AI_INJECT_ROUTER_GUARD

export default router
