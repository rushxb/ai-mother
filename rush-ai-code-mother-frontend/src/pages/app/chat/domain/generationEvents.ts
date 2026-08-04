export type GenerationEventData = Readonly<Record<string, unknown>>

export interface GenerationStreamEvent {
  type: string
  text?: string
  data?: GenerationEventData
}

export interface GenerationEventGap {
  requestedSeq: number
  firstAvailableSeq: number
  recovery: string
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

export const parseGenerationEventSequence = (event: MessageEvent): number | undefined => {
  const raw = typeof event.lastEventId === 'string' ? event.lastEventId.trim() : ''
  if (!/^\d{1,16}$/.test(raw)) return undefined
  const sequence = Number(raw)
  return Number.isSafeInteger(sequence) && sequence > 0 ? sequence : undefined
}

export const parseGenerationEventGap = (event: MessageEvent): GenerationEventGap | undefined => {
  const parsed = parseEventData(event)
  if (!isRecord(parsed)) return undefined
  const requestedSeq = parsed.requestedSeq
  const firstAvailableSeq = parsed.firstAvailableSeq
  const recovery = parsed.recovery
  if (
    typeof requestedSeq !== 'number' ||
    !Number.isSafeInteger(requestedSeq) ||
    requestedSeq < 0 ||
    typeof firstAvailableSeq !== 'number' ||
    !Number.isSafeInteger(firstAvailableSeq) ||
    firstAvailableSeq <= requestedSeq + 1 ||
    typeof recovery !== 'string' ||
    !recovery.trim()
  ) {
    return undefined
  }
  return { requestedSeq, firstAvailableSeq, recovery }
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

export type UserProgressStage =
  | 'understanding'
  | 'planning'
  | 'implementing'
  | 'preview_ready'
  | 'verifying'
  | 'awaiting_approval'
  | 'delivered'

export interface UserProgressEvent {
  stage: UserProgressStage
  message: string
  phase: 'codegen' | 'build' | 'done'
  terminal: boolean
}

const userProgressDefaults: Readonly<Record<UserProgressStage, Omit<UserProgressEvent, 'stage'>>> = {
  understanding: { message: '已理解你的需求', phase: 'codegen', terminal: false },
  planning: { message: '正在确认修改范围', phase: 'codegen', terminal: false },
  implementing: { message: '正在生成或修改代码', phase: 'codegen', terminal: false },
  preview_ready: { message: '已可预览', phase: 'build', terminal: false },
  verifying: { message: '正在做质量校验', phase: 'build', terminal: false },
  awaiting_approval: { message: '需要你确认', phase: 'codegen', terminal: false },
  delivered: { message: '已完成交付', phase: 'done', terminal: true },
}

const isUserProgressStage = (value: unknown): value is UserProgressStage => {
  return typeof value === 'string' && Object.prototype.hasOwnProperty.call(userProgressDefaults, value)
}

const progressFromStage = (stage: UserProgressStage, message?: unknown): UserProgressEvent => {
  const defaults = userProgressDefaults[stage]
  return {
    stage,
    message: typeof message === 'string' && message.trim() ? message.trim() : defaults.message,
    phase: defaults.phase,
    terminal: defaults.terminal,
  }
}

/** 将新版用户阶段、审批事件和必要的旧协议里程碑统一为稳定主进度。 */
export const resolveUserProgressEvent = (
  event: GenerationStreamEvent,
): UserProgressEvent | undefined => {
  if (event.type === 'generation_stage') {
    const stage = event.data?.stage
    if (
      event.data?.audience === 'user' &&
      event.data?.contractVersion === 1 &&
      isUserProgressStage(stage)
    ) {
      return progressFromStage(stage, event.data?.message ?? event.text)
    }
    if (stage === 'codegen_done') {
      return progressFromStage('verifying')
    }
  }
  if (event.type === 'agent_event') {
    const stage = event.data?.userProgressStage
    if (isUserProgressStage(stage)) {
      return progressFromStage(stage, event.data?.userProgressMessage)
    }
    // 兼容旧后端：不再把内部智能体名称用作用户主进度。
    return progressFromStage('implementing')
  }
  if (event.type === 'first_preview_ready') {
    return progressFromStage('preview_ready')
  }
  if (event.type === 'build_result' || event.type === 'dev_server_validation') {
    return progressFromStage('verifying')
  }
  return undefined
}
