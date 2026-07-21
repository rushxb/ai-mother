import axios from 'axios'
import request from '@/request'

export interface GenerationTaskProgress {
  available?: boolean
  progressPercent?: number
  estimatedRemainingMs?: number
  conservativeRemainingMs?: number
  deadlineRisk?: boolean
}

export interface GenerationTaskStatus {
  taskId: string
  appId?: string | number
  route?: string
  status: string
  stage?: string
  stageMessage?: string
  submittedAt?: string
  deadlineAt?: string
  cancellationRequested?: boolean
  cancellationReason?: string
  progress?: GenerationTaskProgress
}

export interface GenerationTaskSubmission {
  taskId: string
  appId?: string | number
  route?: string
  status: string
  submittedAt?: string
  deadlineAt?: string
}

interface BaseResponse<T> {
  code?: number
  data?: T
  message?: string
}

export interface GenerationTaskSubmissionOptions {
  idempotencyKey?: string
  retryTransportFailure?: boolean
}

export const createGenerationTaskIdempotencyKey = () => {
  const cryptoApi = globalThis.crypto
  if (typeof cryptoApi?.randomUUID === 'function') {
    return cryptoApi.randomUUID()
  }
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}

export const submitGenerationTask = async (
  body: { appId: string | number; message: string },
  options: GenerationTaskSubmissionOptions = {},
) => {
  const idempotencyKey = options.idempotencyKey || createGenerationTaskIdempotencyKey()
  const submit = () =>
    request<BaseResponse<GenerationTaskSubmission>>('/generation/tasks', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
      data: body,
    })

  try {
    return await submit()
  } catch (error) {
    const retryableTransportFailure =
      options.retryTransportFailure !== false &&
      axios.isAxiosError(error) &&
      !error.response &&
      error.code !== 'ERR_CANCELED'
    if (!retryableTransportFailure) {
      throw error
    }
    return submit()
  }
}

export const getGenerationTask = (taskId: string) =>
  request<BaseResponse<GenerationTaskStatus>>(`/generation/tasks/${encodeURIComponent(taskId)}`, {
    method: 'GET',
  })

export const getActiveGenerationTaskForApp = (appId: string | number) =>
  request<BaseResponse<GenerationTaskStatus>>(
    `/generation/tasks/by-app/${encodeURIComponent(String(appId))}/active`,
    { method: 'GET' },
  )

export const cancelGenerationTask = (taskId: string) =>
  request<BaseResponse<GenerationTaskStatus>>(
    `/generation/tasks/${encodeURIComponent(taskId)}/cancel`,
    { method: 'POST' },
  )
