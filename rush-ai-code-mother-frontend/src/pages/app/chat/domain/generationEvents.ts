export type GenerationEventData = Readonly<Record<string, unknown>>

export interface GenerationStreamEvent {
  type: string
  text?: string
  data?: GenerationEventData
}

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

const parseEventData = (event: MessageEvent): unknown => {
  if (typeof event.data !== 'string' || !event.data.trim()) {
    return undefined
  }
  try {
    return JSON.parse(event.data) as unknown
  } catch (error) {
    console.error('Failed to parse generation event:', error, event.data)
    return undefined
  }
}

export const parseGenerationStreamEvent = (event: MessageEvent): GenerationStreamEvent | undefined => {
  const parsed = parseEventData(event)
  if (!isRecord(parsed)) {
    return undefined
  }
  // Objects without a type may belong to the legacy { d: string } protocol.
  if (parsed.type === undefined) {
    return undefined
  }
  if (typeof parsed.type !== 'string' || !parsed.type.trim()) {
    console.warn('Ignored malformed generation event:', parsed)
    return undefined
  }
  const result: GenerationStreamEvent = { type: parsed.type }
  if (typeof parsed.text === 'string') {
    result.text = parsed.text
  }
  if (isRecord(parsed.data)) {
    result.data = parsed.data
  }
  return result
}

/** Parses the legacy default-message payload and accepts only { d: string }. */
export const parseLegacyGenerationDelta = (event: MessageEvent): string | undefined => {
  const parsed = parseEventData(event)
  return isRecord(parsed) && typeof parsed.d === 'string' ? parsed.d : undefined
}

/** Exposes only a validated business-error message. */
export const parseGenerationBusinessError = (event: MessageEvent): string | undefined => {
  const parsed = parseEventData(event)
  return isRecord(parsed) && typeof parsed.message === 'string' && parsed.message.trim()
    ? parsed.message.trim()
    : undefined
}

export const getEventString = (data: GenerationEventData | undefined, key: string): string => {
  const value = data?.[key]
  return typeof value === 'string' ? value : ''
}

export const getEventBoolean = (data: GenerationEventData | undefined, key: string): boolean | undefined => {
  const value = data?.[key]
  return typeof value === 'boolean' ? value : undefined
}

export const getEventNumber = (data: GenerationEventData | undefined, key: string): number | undefined => {
  const value = data?.[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

export const getEventRecord = (data: GenerationEventData | undefined, key: string): Record<string, unknown> | undefined => {
  const value = data?.[key]
  return isRecord(value) ? value : undefined
}
