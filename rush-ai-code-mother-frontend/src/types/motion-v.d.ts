declare module 'motion-v' {
  import type { DefineComponent } from 'vue'

  export const Motion: DefineComponent<Record<string, any>>
  export const AnimatePresence: DefineComponent<Record<string, any>>
}
