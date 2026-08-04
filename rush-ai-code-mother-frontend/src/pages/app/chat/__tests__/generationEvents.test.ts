import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  getEventBoolean,
  getEventNumber,
  getEventString,
  parseGenerationBusinessError,
  parseGenerationEventGap,
  parseGenerationEventSequence,
  parseGenerationStreamEvent,
  parseLegacyGenerationDelta,
  resolveUserProgressEvent,
} from '../domain/generationEvents'

const messageEvent = (data: unknown, lastEventId = '') => ({ data, lastEventId } as MessageEvent)

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

  it('accepts only safe positive SSE sequences and validated gap recovery payloads', () => {
    expect(parseGenerationEventSequence(messageEvent('', '42'))).toBe(42)
    expect(parseGenerationEventSequence(messageEvent('', '0'))).toBeUndefined()
    expect(parseGenerationEventSequence(messageEvent('', '9007199254740992'))).toBeUndefined()
    expect(parseGenerationEventGap(messageEvent(JSON.stringify({
      requestedSeq: 12,
      firstAvailableSeq: 41,
      recovery: 'status_snapshot',
    })))).toEqual({ requestedSeq: 12, firstAvailableSeq: 41, recovery: 'status_snapshot' })
    expect(parseGenerationEventGap(messageEvent(JSON.stringify({
      requestedSeq: 12,
      firstAvailableSeq: 13,
      recovery: 'status_snapshot',
    })))).toBeUndefined()
  })

  it('resolves stable user progress without exposing internal agent names', () => {
    expect(resolveUserProgressEvent({
      type: 'generation_stage',
      text: '正在确认修改范围',
      data: {
        audience: 'user',
        contractVersion: 1,
        stage: 'planning',
        message: '正在确认修改范围',
      },
    })).toEqual({
      stage: 'planning',
      message: '正在确认修改范围',
      phase: 'codegen',
      terminal: false,
    })

    expect(resolveUserProgressEvent({
      type: 'agent_event',
      data: { agent: 'DeadlinePolicy', stage: 'model_turn_admission' },
    })?.message).toBe('正在生成或修改代码')
  })

  it('maps approval and preview milestones to user-facing progress', () => {
    expect(resolveUserProgressEvent({
      type: 'agent_event',
      data: {
        userProgressStage: 'awaiting_approval',
        userProgressMessage: '需要你确认',
      },
    })?.stage).toBe('awaiting_approval')
    expect(resolveUserProgressEvent({ type: 'first_preview_ready' })).toEqual({
      stage: 'preview_ready',
      message: '已可预览',
      phase: 'build',
      terminal: false,
    })
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
