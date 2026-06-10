/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string
  readonly VITE_USE_MOCK: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module 'mockjs' {
  interface MockSetupOptions {
    timeout?: string | number
  }

  interface Mockjs {
    setup(options: MockSetupOptions): void
  }

  const Mock: Mockjs
  export default Mock
}
