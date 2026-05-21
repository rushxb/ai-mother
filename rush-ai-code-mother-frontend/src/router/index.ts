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

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
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
