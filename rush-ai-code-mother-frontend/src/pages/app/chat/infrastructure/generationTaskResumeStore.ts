export interface GenerationTaskResume {
  taskId: string
  lastSequence: number
}

const TASK_ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/
const STORAGE_PREFIX = 'rush-ai:generation-task'

const storageKey = (userId: string | number | undefined, appId: string | number) =>
  `${STORAGE_PREFIX}:${String(userId ?? 'anonymous')}:${String(appId)}`

const validResume = (value: unknown): value is GenerationTaskResume => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return false
  }
  const candidate = value as Partial<GenerationTaskResume>
  return (
    typeof candidate.taskId === 'string' &&
    TASK_ID_PATTERN.test(candidate.taskId) &&
    typeof candidate.lastSequence === 'number' &&
    Number.isSafeInteger(candidate.lastSequence) &&
    candidate.lastSequence >= 0
  )
}

export const createGenerationTaskResumeStore = (storage?: Storage) => {
  const remove = (key: string) => {
    try {
      storage?.removeItem(key)
    } catch {
      // Browser privacy modes may deny storage access; resume discovery still works through the API.
    }
  }

  const load = (
    userId: string | number | undefined,
    appId: string | number,
  ): GenerationTaskResume | undefined => {
    if (!storage) return undefined
    const key = storageKey(userId, appId)
    try {
      const raw = storage.getItem(key)
      if (!raw) return undefined
      const parsed = JSON.parse(raw) as unknown
      if (validResume(parsed)) return parsed
      remove(key)
    } catch {
      remove(key)
    }
    return undefined
  }

  const save = (
    userId: string | number | undefined,
    appId: string | number,
    resume: GenerationTaskResume,
  ): GenerationTaskResume => {
    if (!validResume(resume)) {
      throw new Error('Invalid generation task resume state')
    }
    const current = load(userId, appId)
    const next =
      current?.taskId === resume.taskId && current.lastSequence > resume.lastSequence
        ? current
        : resume
    try {
      storage?.setItem(storageKey(userId, appId), JSON.stringify(next))
    } catch {
      // Durable API discovery is the fallback when local persistence is unavailable.
    }
    return next
  }

  const clear = (userId: string | number | undefined, appId: string | number, taskId?: string) => {
    if (!storage) return
    const current = load(userId, appId)
    if (!taskId || !current || current.taskId === taskId) {
      remove(storageKey(userId, appId))
    }
  }

  return { load, save, clear }
}
