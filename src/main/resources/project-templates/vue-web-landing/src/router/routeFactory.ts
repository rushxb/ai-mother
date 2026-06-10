import type { RouteRecordRaw } from 'vue-router'
import routeManifest from './routeManifest.json'

interface RouteManifestItem {
  path: string
  name: string
  component: string
  title?: string
  meta?: Record<string, unknown>
}

// Dynamic import for view components
const viewModules = import.meta.glob('@/views/*.vue')
const pageModules = import.meta.glob('@/pages/*.vue')

export function createRoutes(): RouteRecordRaw[] {
  return (routeManifest as RouteManifestItem[]).map((item) => {
    const route: RouteRecordRaw = {
      path: item.path,
      name: item.name,
      component: viewModules[`/src/views/${item.component}.vue`] || pageModules[`/src/pages/${item.component}.vue`],
      meta: {
        title: item.title,
        ...item.meta
      }
    }
    return route
  })
}

// @AI_INJECT_ROUTE_FACTORY
