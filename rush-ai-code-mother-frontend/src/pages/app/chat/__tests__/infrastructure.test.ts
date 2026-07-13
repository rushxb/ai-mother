import { describe, expect, it } from 'vitest'
import { createAsyncSerialQueue } from '../infrastructure/createAsyncSerialQueue'
import { createVersionGuard } from '../infrastructure/createVersionGuard'

describe('async serial queue', () => {
  it('preserves event order even when handlers await different durations', async () => {
    const completed: number[] = []
    const queue = createAsyncSerialQueue<number>(async (value) => {
      await new Promise((resolve) => setTimeout(resolve, value === 1 ? 10 : 0))
      completed.push(value)
    })

    await Promise.all([queue.enqueue(1), queue.enqueue(2), queue.enqueue(3)])
    expect(completed).toEqual([1, 2, 3])
  })

  it('invalidates tasks that have not started after reset', async () => {
    const completed: number[] = []
    let releaseFirst!: () => void
    const firstGate = new Promise<void>((resolve) => { releaseFirst = resolve })
    const queue = createAsyncSerialQueue<number>(async (value) => {
      if (value === 1) await firstGate
      completed.push(value)
    })

    const first = queue.enqueue(1)
    const stale = queue.enqueue(2)
    await Promise.resolve()
    queue.reset()
    const current = queue.enqueue(3)
    releaseFirst()

    await Promise.all([first, stale, current])
    expect(completed).toContain(1)
    expect(completed).toContain(3)
    expect(completed).not.toContain(2)
  })
})

describe('version guard', () => {
  it('invalidates stale async work with monotonic versions', () => {
    const guard = createVersionGuard()
    const first = guard.next()
    expect(guard.isCurrent(first)).toBe(true)
    const second = guard.invalidate()
    expect(second).toBeGreaterThan(first)
    expect(guard.isCurrent(first)).toBe(false)
    expect(guard.isCurrent(second)).toBe(true)
  })
})
