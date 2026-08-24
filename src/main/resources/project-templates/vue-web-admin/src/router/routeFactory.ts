import type { Component } from 'vue'
import type { RouteRecordRaw } from 'vue-router'
import type { RouteManifest, RouteViewManifest } from '@/types'
import routeManifest from './routeManifest.json'

/**
 * 生产路由只注册清单实际引用的页面，避免把未接入的示例页和实验组件打进生成产物。
 * 新增页面时同时扩展清单与此注册表，缺失映射会在启动前直接报错。
 */
const routeComponents: Record<string, Component> = {
  DashboardView: () => import('@/views/DashboardView.vue'),
  LoginPage: () => import('@/pages/LoginPage.vue'),
  ForbiddenPage: () => import('@/pages/ForbiddenPage.vue')
}

export function createRoutes(): RouteRecordRaw[] {
  return (routeManifest as RouteManifest[]).map((item) => {
    if ('redirect' in item) {
      return { path: item.path, redirect: item.redirect }
    }
    return createViewRoute(item)
  })
}

function createViewRoute(item: RouteViewManifest): RouteRecordRaw {
  const component = routeComponents[item.component]
  if (!component) {
    throw new Error(`Route component is not registered: ${item.component}`)
  }
  return {
    path: item.path,
    name: item.name,
    component,
    meta: {
      title: item.title,
      layout: item.layout,
      ...item.meta
    }
  }
}

// @AI_INJECT_ROUTE_FACTORY
