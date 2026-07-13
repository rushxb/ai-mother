import { afterEach, describe, expect, it, vi } from 'vitest'
import { normalizeImageUrl } from '../url'

afterEach(() => vi.unstubAllGlobals())

const stubWindowLocation = () => {
  vi.stubGlobal('window', {
    location: {
      origin: 'https://console.example',
      protocol: 'https:',
    },
  })
}

describe('image URL policy', () => {
  it('accepts HTTP(S), host-only and root-relative image URLs', () => {
    stubWindowLocation()

    expect(normalizeImageUrl('https://cdn.example/cover.png')).toBe('https://cdn.example/cover.png')
    expect(normalizeImageUrl('http://cdn.example/cover.jpg')).toBe('http://cdn.example/cover.jpg')
    expect(normalizeImageUrl('cdn.example/cover.webp')).toBe('https://cdn.example/cover.webp')
    expect(normalizeImageUrl('/assets/cover.png')).toBe('/assets/cover.png')
  })

  it('accepts restricted raster data images but rejects SVG data images', () => {
    expect(normalizeImageUrl('data:image/png;base64,aGVsbG8=')).toBe(
      'data:image/png;base64,aGVsbG8=',
    )
    expect(normalizeImageUrl('data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=')).toBe('')
  })

  it('allows only same-origin blob images', () => {
    stubWindowLocation()

    expect(normalizeImageUrl('blob:https://console.example/123')).toBe(
      'blob:https://console.example/123',
    )
    expect(normalizeImageUrl('blob:https://evil.example/123')).toBe('')
  })

  it.each([
    'javascript:alert(1)',
    'file:///etc/passwd',
    'ftp://files.example/image.png',
    '/\\evil.example/image.png',
    'not a valid url',
    '',
  ])('rejects unsafe or malformed input: %s', (value) => {
    stubWindowLocation()
    expect(normalizeImageUrl(value)).toBe('')
  })
})
