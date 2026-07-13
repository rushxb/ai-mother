import { createRouter, createWebHistory } from 'vue-router'

const getDynamicBaseUrl = () => {
  if (typeof window === 'undefined') {
    return '/'
  }

  const match = window.location.pathname.match(/\/sites\/[^/]+\/?/)
  return match?.[0] ?? '/'
}

const adminMeta = { requiresAuth: true, requiredRole: 'admin' } as const

const router = createRouter({
  history: createWebHistory(getDynamicBaseUrl()),
  routes: [
    { path: '/', name: 'home', component: () => import('@/pages/HomePage.vue') },
    { path: '/user/login', name: 'user-login', component: () => import('@/pages/user/UserAuthPage.vue') },
    { path: '/user/register', name: 'user-register', component: () => import('@/pages/user/UserAuthPage.vue') },
    {
      path: '/user/success',
      name: 'user-login-success',
      component: () => import('@/pages/user/UserLoginSuccessPage.vue'),
      meta: { requiresAuth: true },
    },
    { path: '/admin/userManage', name: 'admin-users', component: () => import('@/pages/admin/UserManagePage.vue'), meta: adminMeta },
    { path: '/admin/appManage', name: 'admin-apps', component: () => import('@/pages/admin/AppManagePage.vue'), meta: adminMeta },
    { path: '/admin/chatManage', name: 'admin-chats', component: () => import('@/pages/admin/ChatManagePage.vue'), meta: adminMeta },
    { path: '/admin/modelManage', name: 'admin-models', component: () => import('@/pages/admin/ModelManagePage.vue'), meta: adminMeta },
    { path: '/admin/generationPerformance', name: 'admin-generation-performance', component: () => import('@/pages/admin/GenerationPerformancePage.vue'), meta: adminMeta },
    { path: '/app/chat/:id', name: 'app-chat', component: () => import('@/pages/app/AppChatPage.vue'), meta: { requiresAuth: true } },
    { path: '/app/edit/:id', name: 'app-edit', component: () => import('@/pages/app/AppEditPage.vue'), meta: { requiresAuth: true } },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/pages/error/NotFoundPage.vue') },
  ],
})

export default router
