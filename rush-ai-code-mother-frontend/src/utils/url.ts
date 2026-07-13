const SAFE_NETWORK_PROTOCOLS = new Set(['http:', 'https:'])
const SAFE_DATA_IMAGE_PATTERN = /^data:image\/(?:png|jpe?g|gif|webp|avif);base64,[a-z\d+/=\s]+$/i
const EXPLICIT_SCHEME_PATTERN = /^[a-z][a-z\d+.-]*:/i

const getPreferredProtocol = () => {
  if (typeof window !== 'undefined' && SAFE_NETWORK_PROTOCOLS.has(window.location.protocol)) {
    return window.location.protocol
  }
  return 'https:'
}

const normalizeNetworkUrl = (value: string) => {
  const candidate = value.startsWith('//')
    ? `${getPreferredProtocol()}${value}`
    : EXPLICIT_SCHEME_PATTERN.test(value)
      ? value
      : `${getPreferredProtocol()}//${value}`

  try {
    const parsedUrl = new URL(candidate)
    return SAFE_NETWORK_PROTOCOLS.has(parsedUrl.protocol) ? parsedUrl.toString() : ''
  } catch {
    return ''
  }
}

const normalizeBlobUrl = (value: string) => {
  if (typeof window === 'undefined') {
    return ''
  }

  try {
    const parsedUrl = new URL(value)
    return parsedUrl.protocol === 'blob:' && parsedUrl.origin === window.location.origin
      ? parsedUrl.toString()
      : ''
  } catch {
    return ''
  }
}

/**
 * Normalizes image URLs while rejecting executable or untrusted protocols.
 * SVG data URLs are intentionally excluded because they can contain active content.
 */
export const normalizeImageUrl = (url?: string) => {
  const value = url?.trim()
  if (!value || value.includes('\\')) {
    return ''
  }
  if (value.startsWith('/') && !value.startsWith('//')) {
    return value
  }
  if (SAFE_DATA_IMAGE_PATTERN.test(value)) {
    return value
  }
  if (value.toLowerCase().startsWith('blob:')) {
    return normalizeBlobUrl(value)
  }
  return normalizeNetworkUrl(value)
}
