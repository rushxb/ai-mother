import { createRouter, createWebHashHistory } from 'vue-router'
import routeManifest from './routeManifest.json'
import { createRoutesFromManifest } from './routeFactory'

const routes = createRoutesFromManifest(routeManifest)

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.afterEach((to) => {
  document.title = `${to.meta.title || '后台'} - Vue Web Admin`
})

export default router
