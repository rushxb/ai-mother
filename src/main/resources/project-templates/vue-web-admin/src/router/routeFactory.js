const viewModules = import.meta.glob('../views/**/*.vue')
const pageModules = import.meta.glob('../pages/**/*.vue')

function resolveView(component) {
  const normalized = component.endsWith('.vue') ? component : `${component}.vue`
  const candidates = [
    `../views/${normalized}`,
    `../views/${component}/index.vue`,
    `../pages/${normalized}`,
    `../pages/${component}/index.vue`
  ]
  const modules = {
    ...viewModules,
    ...pageModules
  }

  const matchedPath = candidates.find((path) => modules[path])
  if (!matchedPath) {
    throw new Error(`Route component not found in src/views or src/pages: ${component}`)
  }

  return modules[matchedPath]
}

export function createRoutesFromManifest(manifest) {
  return manifest.map((item) => {
    if (item.redirect) {
      return { path: item.path, redirect: item.redirect }
    }

    return {
      path: item.path,
      name: item.name,
      component: resolveView(item.component),
      meta: {
        title: item.title,
        layout: item.layout,
        requiresAuth: Boolean(item.requiresAuth)
      }
    }
  })
}
