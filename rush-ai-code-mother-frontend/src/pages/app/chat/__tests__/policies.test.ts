import { afterEach, describe, expect, it, vi } from 'vitest'
import { canTransitionGenerationPhase } from '../domain/generationState'
import { normalizeExternalUrl, openExternalUrl } from '../infrastructure/previewUrlPolicy'

afterEach(() => vi.unstubAllGlobals())

describe('generation phase transitions', () => {
  it('allows supported lifecycle transitions and rejects regressions', () => {
    expect(canTransitionGenerationPhase('idle', 'codegen')).toBe(true)
    expect(canTransitionGenerationPhase('codegen', 'build')).toBe(true)
    expect(canTransitionGenerationPhase('build', 'repair')).toBe(true)
    expect(canTransitionGenerationPhase('done', 'repair')).toBe(false)
  })
})

describe('external preview URL policy', () => {
  it('allows only HTTP(S) URLs and opens without an opener', () => {
    const openedWindow = { opener: {} }
    const open = vi.fn(() => openedWindow)
    vi.stubGlobal('window', {
      location: { origin: 'https://console.example' },
      open,
    })

    expect(normalizeExternalUrl('/api/preview')).toBe('https://console.example/api/preview')
    expect(normalizeExternalUrl('javascript:alert(1)')).toBeUndefined()
    expect(openExternalUrl('https://preview.example/app')).toBe(true)
    expect(open).toHaveBeenCalledWith(
      'https://preview.example/app',
      '_blank',
      'noopener,noreferrer',
    )
    expect(openedWindow.opener).toBeNull()
  })
})
