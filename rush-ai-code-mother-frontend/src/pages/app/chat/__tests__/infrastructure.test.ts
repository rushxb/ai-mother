import { describe, expect, it } from 'vitest'
import { createAsyncSerialQueue } from '../infrastructure/createAsyncSerialQueue'
import { createVersionGuard } from '../infrastructure/createVersionGuard'
import { createGenerationTaskResumeStore } from '../infrastructure/generationTaskResumeStore'

const createMemoryStorage = (): Storage => {
  const values = new Map<string, string>()
  return {
    get length() { return values.size },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => Array.from(values.keys())[index] ?? null,
    removeItem: (key) => { values.delete(key) },
    setItem: (key, value) => { values.set(key, value) },
  }
}

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

describe('generation task resume store', () => {
  it('persists the newest sequence without allowing cursor regression', () => {
    const store = createGenerationTaskResumeStore(createMemoryStorage())
    store.save(7, 11, { taskId: 'task-1', lastSequence: 8 })
    store.save(7, 11, { taskId: 'task-1', lastSequence: 3 })

    expect(store.load(7, 11)).toEqual({ taskId: 'task-1', lastSequence: 8 })
  })

  it('isolates users and applications and clears only the expected task', () => {
    const store = createGenerationTaskResumeStore(createMemoryStorage())
    store.save(7, 11, { taskId: 'task-1', lastSequence: 2 })
    store.save(8, 11, { taskId: 'task-2', lastSequence: 4 })
    store.clear(7, 11, 'another-task')
    expect(store.load(7, 11)?.taskId).toBe('task-1')
    store.clear(7, 11, 'task-1')
    expect(store.load(7, 11)).toBeUndefined()
    expect(store.load(8, 11)?.taskId).toBe('task-2')
  })
})
