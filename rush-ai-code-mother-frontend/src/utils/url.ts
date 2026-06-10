const ABSOLUTE_URL_PATTERN = /^[a-z][a-z\d+\-.]*:\/\//i

const getPreferredProtocol = () => {
  if (typeof window !== 'undefined' && window.location?.protocol) {
    return window.location.protocol
  }
  return 'https:'
}

export const normalizeImageUrl = (url?: string) => {
  const value = url?.trim()
  if (!value) {
    return ''
  }
  if (ABSOLUTE_URL_PATTERN.test(value) || value.startsWith('data:') || value.startsWith('blob:')) {
    return value
  }
  if (value.startsWith('//')) {
    return `${getPreferredProtocol()}${value}`
  }
  if (value.startsWith('/')) {
    return value
  }
  return `${getPreferredProtocol()}//${value}`
}
