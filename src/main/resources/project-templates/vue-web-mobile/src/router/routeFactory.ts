import type { Component } from 'vue'
import type { RouteRecordRaw } from 'vue-router'
import routeManifest from './routeManifest.json'

interface RouteManifestItem {
  path: string
  name: string
  component: string
  title?: string
  layout?: string
  requiresAuth?: boolean
  meta?: Record<string, unknown>
}

const viewModules = import.meta.glob<{ default: Component }>('../views/**/*.vue')
const pageModules = import.meta.glob<{ default: Component }>('../pages/**/*.vue')

function resolveView(component: string) {
  const normalized = component.endsWith('.vue') ? component : `${component}.vue`
  const modules = { ...viewModules, ...pageModules }
  const candidates = [
    `../views/${normalized}`,
    `../views/${component}/index.vue`,
    `../pages/${normalized}`,
    `../pages/${component}/index.vue`
  ]
  const matchedPath = candidates.find(candidate => modules[candidate])
  if (!matchedPath) {
    throw new Error(`Route component not found in src/views or src/pages: ${component}`)
  }
  return modules[matchedPath]
}

export function createRoutes(): RouteRecordRaw[] {
  return (routeManifest as RouteManifestItem[]).map(item => ({
    path: item.path,
    name: item.name,
    component: resolveView(item.component),
    meta: {
      title: item.title,
      layout: item.layout,
      requiresAuth: Boolean(item.requiresAuth),
      ...item.meta
    }
  }))
}

// @AI_INJECT_ROUTE_FACTORY
