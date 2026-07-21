import { AxiosError } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/request', () => ({
  default: vi.fn(),
}))

import request from '@/request'
import {
  createGenerationTaskIdempotencyKey,
  submitGenerationTask,
} from '@/api/generationTaskController'

const mockedRequest = vi.mocked(request)

describe('generation task submission idempotency', () => {
  beforeEach(() => {
    mockedRequest.mockReset()
  })

  it('reuses one Idempotency-Key when retrying a response-loss transport failure', async () => {
    mockedRequest
      .mockRejectedValueOnce(new AxiosError('timeout', 'ECONNABORTED'))
      .mockResolvedValueOnce({ data: { code: 0, data: { taskId: 'task-1', status: 'queued' } } })

    await submitGenerationTask({ appId: 11, message: 'build dashboard' }, {
      idempotencyKey: 'submission-key',
    })

    expect(mockedRequest).toHaveBeenCalledTimes(2)
    expect(mockedRequest.mock.calls[0]?.[1]?.headers).toMatchObject({
      'Idempotency-Key': 'submission-key',
    })
    expect(mockedRequest.mock.calls[1]?.[1]?.headers).toMatchObject({
      'Idempotency-Key': 'submission-key',
    })
  })

  it('does not retry a server response and generates visible-ASCII keys', async () => {
    const responseFailure = new AxiosError(
      'conflict',
      'ERR_BAD_RESPONSE',
      undefined,
      undefined,
      { status: 409 } as never,
    )
    mockedRequest.mockRejectedValueOnce(responseFailure)

    await expect(submitGenerationTask(
      { appId: 11, message: 'build dashboard' },
      { idempotencyKey: 'submission-key' },
    )).rejects.toBe(responseFailure)

    expect(mockedRequest).toHaveBeenCalledTimes(1)
    expect(createGenerationTaskIdempotencyKey()).toMatch(/^[\x21-\x7e]{1,255}$/)
  })
})
