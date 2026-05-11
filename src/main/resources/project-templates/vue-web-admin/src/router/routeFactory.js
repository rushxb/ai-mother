const viewModules = import.meta.glob('../views/**/*.vue')

function resolveView(component) {
  const normalized = component.endsWith('.vue') ? component : `${component}.vue`
  const candidates = [
    `../views/${normalized}`,
    `../views/${component}/index.vue`
  ]

  const matchedPath = candidates.find((path) => viewModules[path])
  if (!matchedPath) {
    throw new Error(`Route component not found in src/views: ${component}`)
  }

  return viewModules[matchedPath]
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
