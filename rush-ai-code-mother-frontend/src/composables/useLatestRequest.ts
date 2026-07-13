import { onBeforeUnmount, ref } from 'vue'

/**
 * Prevents stale responses from overwriting newer state and keeps loading accurate
 * while multiple requests overlap.
 */
export const useLatestRequest = () => {
  const loading = ref(false)
  let latestRequestId = 0
  let activeRequestCount = 0
  let disposed = false

  const begin = () => {
    const requestId = ++latestRequestId
    activeRequestCount += 1
    loading.value = true
    return requestId
  }

  const isLatest = (requestId: number) => !disposed && requestId === latestRequestId

  const end = (requestId: number) => {
    activeRequestCount = Math.max(0, activeRequestCount - 1)
    if (!disposed && (requestId === latestRequestId || activeRequestCount === 0)) {
      loading.value = activeRequestCount > 0
    }
  }

  const invalidate = () => {
    latestRequestId += 1
  }

  onBeforeUnmount(() => {
    disposed = true
    invalidate()
  })

  return { loading, begin, isLatest, end, invalidate }
}
