/**
 * 沙箱安全的本地存储适配器。
 *
 * 预览与部署产物以 CSP `sandbox` 指令提供，未授予 allow-same-origin，
 * 此时访问 `localStorage` 会抛 SecurityError。直接在模块顶层访问会导致整页白屏，
 * 因此统一通过本适配器访问：可用时走真实 localStorage，不可用时退化为内存存储。
 *
 * 内存存储在刷新后丢失，这是沙箱预览环境的预期行为；正式部署到独立域名后
 * 真实 localStorage 会自动生效，业务代码无需改动。
 */

/** 与 Storage 接口保持结构兼容，便于直接交给 pinia-plugin-persistedstate 使用。 */
type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem' | 'clear' | 'key' | 'length'>

function createMemoryStorage(): StorageLike {
  const entries = new Map<string, string>()
  return {
    get length() {
      return entries.size
    },
    getItem: (key: string) => (entries.has(key) ? (entries.get(key) as string) : null),
    setItem: (key: string, value: string) => {
      entries.set(key, String(value))
    },
    removeItem: (key: string) => {
      entries.delete(key)
    },
    clear: () => {
      entries.clear()
    },
    key: (index: number) => Array.from(entries.keys())[index] ?? null
  }
}

/** 探测真实存储是否可用：沙箱、隐私模式和配额耗尽都会在此暴露。 */
function resolveStorage(): StorageLike {
  try {
    const probeKey = '__storage_probe__'
    window.localStorage.setItem(probeKey, '1')
    window.localStorage.removeItem(probeKey)
    return window.localStorage
  } catch {
    return createMemoryStorage()
  }
}

export const safeLocalStorage: StorageLike = resolveStorage()

/** 真实 localStorage 是否可用；供需要提示用户「刷新后不保留」的场景使用。 */
export const isPersistentStorageAvailable: boolean = safeLocalStorage === window.localStorage
