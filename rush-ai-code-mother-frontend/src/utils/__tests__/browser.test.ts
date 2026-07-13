import { afterEach, describe, expect, it, vi } from 'vitest'
import { openInNewTab, resolveSafeBrowserUrl } from '../browser'

afterEach(() => vi.unstubAllGlobals())

const stubWindow = (open = vi.fn()) => {
  vi.stubGlobal('window', {
    location: { origin: 'https://console.example' },
    open,
  })
  return open
}

describe('safe browser navigation', () => {
  it('resolves only HTTP(S) targets', () => {
    stubWindow()

    expect(resolveSafeBrowserUrl('/preview/1')).toBe('https://console.example/preview/1')
    expect(resolveSafeBrowserUrl('https://preview.example/app')).toBe('https://preview.example/app')
    expect(resolveSafeBrowserUrl('javascript:alert(1)')).toBeNull()
    expect(resolveSafeBrowserUrl('data:text/html,unsafe')).toBeNull()
    expect(resolveSafeBrowserUrl('file:///tmp/unsafe')).toBeNull()
  })

  it('opens a new tab without an opener', () => {
    const openedWindow = { opener: {} as unknown }
    const open = stubWindow(vi.fn(() => openedWindow))

    expect(openInNewTab('https://preview.example/app')).toBe(true)
    expect(open).toHaveBeenCalledWith(
      'https://preview.example/app',
      '_blank',
      'noopener,noreferrer',
    )
    expect(openedWindow.opener).toBeNull()
  })

  it('does not call window.open for unsafe URLs and reports popup blocking', () => {
    const open = stubWindow(vi.fn(() => null))

    expect(openInNewTab('javascript:alert(1)')).toBe(false)
    expect(open).not.toHaveBeenCalled()
    expect(openInNewTab('https://preview.example/app')).toBe(false)
    expect(open).toHaveBeenCalledTimes(1)
  })
})
