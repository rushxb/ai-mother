const ABSOLUTE_URL_PATTERN = /^[a-z][a-z\d+\-.]*:\/\//i

export const normalizeImageUrl = (url?: string) => {
  const value = url?.trim()
  if (!value) {
    return ''
  }
  if (ABSOLUTE_URL_PATTERN.test(value) || value.startsWith('data:') || value.startsWith('blob:')) {
    return value
  }
  if (value.startsWith('//')) {
    return `http:${value}`
  }
  if (value.startsWith('/')) {
    return value
  }
  return `http://${value}`
}
