export interface AsyncSerialQueue<T> {
  enqueue(value: T): Promise<void>
  reset(): void
}

/**
 * 将异步事件严格按入队顺序执行。reset 会让尚未开始的旧会话任务失效，
 * 已执行任务仍需结合会话版本守卫避免提交过期结果。
 */
export const createAsyncSerialQueue = <T>(handler: (value: T) => Promise<void>): AsyncSerialQueue<T> => {
  let tail = Promise.resolve()
  let generation = 0

  return {
    enqueue(value) {
      const queuedGeneration = generation
      const task = tail.then(async () => {
        if (queuedGeneration !== generation) {
          return
        }
        await handler(value)
      })
      tail = task.catch((error) => {
        console.error('串行事件处理失败:', error)
      })
      return task
    },
    reset() {
      generation += 1
      tail = Promise.resolve()
    },
  }
}
