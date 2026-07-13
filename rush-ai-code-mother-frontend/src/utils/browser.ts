const SAFE_EXTERNAL_PROTOCOLS = new Set(['http:', 'https:'])

export const resolveSafeBrowserUrl = (value: string) => {
  if (typeof window === 'undefined') {
    return null
  }

  try {
    const url = new URL(value, window.location.origin)
    return SAFE_EXTERNAL_PROTOCOLS.has(url.protocol) ? url.toString() : null
  } catch {
    return null
  }
}

export const openInNewTab = (value: string) => {
  const safeUrl = resolveSafeBrowserUrl(value)
  if (!safeUrl) {
    return false
  }

  const openedWindow = window.open(safeUrl, '_blank', 'noopener,noreferrer')
  if (openedWindow) {
    openedWindow.opener = null
  }
  return Boolean(openedWindow)
}
