export interface VersionGuard {
  current(): number
  next(): number
  invalidate(): number
  isCurrent(version: number): boolean
}

/** 用单调递增版本号阻止旧路由会话或旧请求覆盖最新状态。 */
export const createVersionGuard = (): VersionGuard => {
  let version = 0
  return {
    current: () => version,
    next: () => ++version,
    invalidate: () => ++version,
    isCurrent: (candidate) => candidate === version,
  }
}
