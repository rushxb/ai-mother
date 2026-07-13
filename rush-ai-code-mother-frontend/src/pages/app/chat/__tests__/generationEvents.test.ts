import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  getEventBoolean,
  getEventNumber,
  getEventString,
  parseGenerationBusinessError,
  parseGenerationStreamEvent,
  parseLegacyGenerationDelta,
} from '../domain/generationEvents'

const messageEvent = (data: unknown) => ({ data } as MessageEvent)

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
  vi.spyOn(console, 'warn').mockImplementation(() => undefined)
})

afterEach(() => vi.restoreAllMocks())

describe('generation event protocol', () => {
  it('accepts a valid event and exposes only typed values', () => {
    const event = parseGenerationStreamEvent(messageEvent(JSON.stringify({
      type: 'build_result',
      text: 'done',
      data: { stage: 'build', success: true, round: 2, invalid: null },
    })))

    expect(event).toEqual({
      type: 'build_result',
      text: 'done',
      data: { stage: 'build', success: true, round: 2, invalid: null },
    })
    expect(getEventString(event?.data, 'stage')).toBe('build')
    expect(getEventBoolean(event?.data, 'success')).toBe(true)
    expect(getEventNumber(event?.data, 'round')).toBe(2)
    expect(getEventString(event?.data, 'invalid')).toBe('')
  })

  it('validates legacy deltas and business errors independently', () => {
    expect(parseLegacyGenerationDelta(messageEvent(JSON.stringify({ d: 'delta' })))).toBe('delta')
    expect(parseLegacyGenerationDelta(messageEvent(JSON.stringify({ d: 1 })))).toBeUndefined()
    expect(parseGenerationBusinessError(messageEvent(JSON.stringify({ message: ' quota exceeded ' })))).toBe('quota exceeded')
    expect(parseGenerationBusinessError(messageEvent(JSON.stringify({ message: { unsafe: true } })))).toBeUndefined()
  })

  it.each([
    '',
    'not-json',
    JSON.stringify(null),
    JSON.stringify([]),
    JSON.stringify({ type: 123 }),
    JSON.stringify({ data: {} }),
  ])('rejects malformed input: %s', (data) => {
    expect(parseGenerationStreamEvent(messageEvent(data))).toBeUndefined()
  })
})
