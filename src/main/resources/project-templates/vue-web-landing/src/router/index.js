import { createRouter, createWebHashHistory } from 'vue-router'
import routeManifest from './routeManifest.json'
import { createRoutesFromManifest } from './routeFactory'

const router = createRouter({
  history: createWebHashHistory(),
  routes: createRoutesFromManifest(routeManifest)
})

router.afterEach(() => {
  document.title = 'Vue Web Landing'
})

export default router
