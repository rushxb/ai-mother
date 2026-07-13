import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyTextToClipboard } from '../clipboard'

afterEach(() => vi.unstubAllGlobals())

describe('copyTextToClipboard', () => {
  it('returns false outside a browser document', async () => {
    vi.stubGlobal('document', undefined)

    await expect(copyTextToClipboard('content')).resolves.toBe(false)
  })

  it('uses the Clipboard API when available', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('document', {})
    vi.stubGlobal('navigator', { clipboard: { writeText } })

    await expect(copyTextToClipboard('content')).resolves.toBe(true)
    expect(writeText).toHaveBeenCalledWith('content')
  })

  it('falls back to a temporary textarea when Clipboard API access fails', async () => {
    const remove = vi.fn()
    const select = vi.fn()
    const setSelectionRange = vi.fn()
    const textarea = {
      dataset: {},
      remove,
      select,
      setAttribute: vi.fn(),
      setSelectionRange,
      style: {},
      value: '',
    }
    const appendChild = vi.fn()
    const execCommand = vi.fn(() => true)

    vi.stubGlobal('navigator', {
      clipboard: {
        writeText: vi.fn().mockRejectedValue(new Error('permission denied')),
      },
    })
    vi.stubGlobal('document', {
      body: { appendChild },
      createElement: vi.fn(() => textarea),
      execCommand,
    })

    await expect(copyTextToClipboard('fallback content')).resolves.toBe(true)
    expect(textarea.value).toBe('fallback content')
    expect(appendChild).toHaveBeenCalledWith(textarea)
    expect(select).toHaveBeenCalledOnce()
    expect(setSelectionRange).toHaveBeenCalledWith(0, 16)
    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(remove).toHaveBeenCalledOnce()
  })
})
