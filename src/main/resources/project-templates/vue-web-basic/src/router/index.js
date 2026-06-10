import { createRouter, createWebHashHistory } from 'vue-router'
import routeManifest from './routeManifest.json'
import { createRoutesFromManifest } from './routeFactory'

const routes = createRoutesFromManifest(routeManifest)

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

router.afterEach((to) => {
  document.title = `${to.meta.title || '页面'} - Vue Web Basic`
})

export default router
