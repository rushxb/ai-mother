import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '@/pages/HomePage.vue'
import UserAuthPage from '@/pages/user/UserAuthPage.vue'
import UserLoginSuccessPage from '@/pages/user/UserLoginSuccessPage.vue'
import UserManagePage from '@/pages/admin/UserManagePage.vue'
import AppManagePage from '@/pages/admin/AppManagePage.vue'
import AppChatPage from '@/pages/app/AppChatPage.vue'
import AppEditPage from '@/pages/app/AppEditPage.vue'
import ChatManagePage from "@/pages/admin/ChatManagePage.vue";
import ModelManagePage from "@/pages/admin/ModelManagePage.vue";
import GenerationPerformancePage from '@/pages/admin/GenerationPerformancePage.vue'
import TokenDashboardPage from '@/pages/admin/TokenDashboardPage.vue'
import ApiTraceDashboardPage from '@/pages/admin/ApiTraceDashboardPage.vue'

// 获取动态的 history base 路径，确保在根目录或子目录（如 MCP 静态网站部署）下路由都能正常工作
const getDynamicBaseUrl = () => {
  const pathname = window.location.pathname;
  if (pathname.includes('/sites/')) {
    const match = pathname.match(/\/sites\/[^/]+\/?/);
    return match ? match[0] : '/';
  }
  return '/';
};

const router = createRouter({
  history: createWebHistory(getDynamicBaseUrl()),
  routes: [
    {
      path: '/',
      name: '主页',
      component: HomePage,
    },
    {
      path: '/user/login',
      name: '用户登录',
      component: UserAuthPage,
    },
    {
      path: '/user/register',
      name: '用户注册',
      component: UserAuthPage,
    },
    {
      path: '/user/success',
      name: '登录成功过渡',
      component: UserLoginSuccessPage,
    },
    {
      path: '/admin/userManage',
      name: '用户管理',
      component: UserManagePage,
    },
    {
      path: '/admin/appManage',
      name: '应用管理',
      component: AppManagePage,
    },
    {
      path: '/admin/chatManage',
      name: '对话管理',
      component: ChatManagePage,
    },
    {
      path: '/admin/modelManage',
      name: '模型管理',
      component: ModelManagePage,
    },
    {
      path: '/admin/generationPerformance',
      name: '生成耗时',
      component: GenerationPerformancePage,
    },
    {
      path: '/admin/tokenDashboard',
      name: 'Token 监控',
      component: TokenDashboardPage,
    },
    {
      path: '/admin/apiTraceDashboard',
      name: '接口观测',
      component: ApiTraceDashboardPage,
    },
    {
      path: '/app/chat/:id',
      name: '应用对话',
      component: AppChatPage,
    },
    {
      path: '/app/edit/:id',
      name: '编辑应用',
      component: AppEditPage,
    },
  ],
})

export default router
