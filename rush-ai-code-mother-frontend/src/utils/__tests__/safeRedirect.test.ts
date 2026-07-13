import { afterEach, describe, expect, it, vi } from 'vitest'
import { getCurrentRoutePath, getSafeSameOriginPath } from '../safeRedirect'

afterEach(() => vi.unstubAllGlobals())

const stubWindowLocation = () => {
  vi.stubGlobal('window', {
    location: {
      origin: 'https://console.example',
      pathname: '/admin/models',
      search: '?page=2',
      hash: '#details',
    },
  })
}

describe('same-origin redirect policy', () => {
  it('accepts root-relative and same-origin absolute targets', () => {
    stubWindowLocation()

    expect(getSafeSameOriginPath('/apps/12?tab=preview#top')).toBe('/apps/12?tab=preview#top')
    expect(getSafeSameOriginPath('https://console.example/user/profile?from=login')).toBe(
      '/user/profile?from=login',
    )
  })

  it('rejects external, protocol-relative, backslash and malformed targets', () => {
    stubWindowLocation()

    expect(getSafeSameOriginPath('https://evil.example/phishing')).toBe('/')
    expect(getSafeSameOriginPath('//evil.example/phishing')).toBe('/')
    expect(getSafeSameOriginPath('/\\evil.example/phishing')).toBe('/')
    expect(getSafeSameOriginPath('http://[invalid')).toBe('/')
  })

  it('handles empty and non-string values and supports a custom fallback', () => {
    stubWindowLocation()

    expect(getSafeSameOriginPath(undefined)).toBe('/')
    expect(getSafeSameOriginPath(['/apps/1'])).toBe('/')
    expect(getSafeSameOriginPath('', '/safe')).toBe('/safe')
    expect(getSafeSameOriginPath('', 'https://evil.example/fallback')).toBe('/')
  })

  it('builds the current route without exposing a full external URL', () => {
    stubWindowLocation()
    expect(getCurrentRoutePath()).toBe('/admin/models?page=2#details')
  })
})
