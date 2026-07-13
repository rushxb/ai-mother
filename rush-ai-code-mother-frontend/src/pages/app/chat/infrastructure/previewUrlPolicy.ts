const ALLOWED_EXTERNAL_PROTOCOLS = new Set(['http:', 'https:'])

export const normalizeExternalUrl = (rawUrl: string): string | undefined => {
  if (!rawUrl.trim() || typeof window === 'undefined') {
    return undefined
  }
  try {
    const url = new URL(rawUrl, window.location.origin)
    return ALLOWED_EXTERNAL_PROTOCOLS.has(url.protocol) ? url.href : undefined
  } catch {
    return undefined
  }
}

/** 安全打开外部页面，避免反向标签劫持及 javascript/data 协议执行。 */
export const openExternalUrl = (rawUrl: string): boolean => {
  const safeUrl = normalizeExternalUrl(rawUrl)
  if (!safeUrl) {
    return false
  }
  const openedWindow = window.open(safeUrl, '_blank', 'noopener,noreferrer')
  if (openedWindow) {
    openedWindow.opener = null
  }
  return true
}
