import { createRouter, createWebHistory } from 'vue-router'
import { createRoutes } from './routeFactory'

const router = createRouter({
  history: createWebHistory(),
  routes: createRoutes()
})

// Navigation guard
router.beforeEach((to) => {
  // Set page title
  const title = to.meta.title as string
  if (title) {
    document.title = `${title} - ${import.meta.env.VITE_APP_TITLE || 'Landing'}`
  }
})

// @AI_INJECT_ROUTER_GUARD

export default router
