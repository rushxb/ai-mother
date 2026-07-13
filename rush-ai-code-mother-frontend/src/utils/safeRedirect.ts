const FALLBACK_PATH = '/'

const normalizeFallbackPath = (fallback: string) => {
  const value = fallback.trim()
  return value.startsWith('/') && !value.startsWith('//') && !value.includes('\\')
    ? value
    : FALLBACK_PATH
}

export const getSafeSameOriginPath = (value: unknown, fallback = FALLBACK_PATH) => {
  const safeFallback = normalizeFallbackPath(fallback)
  if (typeof value !== 'string') {
    return safeFallback
  }

  const candidate = value.trim()
  if (!candidate || candidate.includes('\\')) {
    return safeFallback
  }

  if (typeof window === 'undefined') {
    return candidate.startsWith('/') && !candidate.startsWith('//') ? candidate : safeFallback
  }

  try {
    const targetUrl = new URL(candidate, window.location.origin)
    if (targetUrl.origin !== window.location.origin) {
      return safeFallback
    }
    return `${targetUrl.pathname}${targetUrl.search}${targetUrl.hash}`
  } catch {
    return safeFallback
  }
}

export const getCurrentRoutePath = () => {
  if (typeof window === 'undefined') {
    return FALLBACK_PATH
  }
  return `${window.location.pathname}${window.location.search}${window.location.hash}`
}
