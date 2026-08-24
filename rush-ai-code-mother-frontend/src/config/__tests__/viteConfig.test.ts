import { describe, expect, it } from 'vitest'
import type { ProxyOptions, UserConfig } from 'vite'

import viteConfig from '../../../vite.config'

describe('开发代理配置', () => {
  it('必须转发 Preview 同源路径上的 HMR WebSocket', () => {
    const proxy = (viteConfig as UserConfig).server?.proxy as
      | Record<string, string | ProxyOptions>
      | undefined
    const apiProxy = proxy?.['/api']

    expect(apiProxy).toBeTypeOf('object')
    expect(apiProxy).toMatchObject({ ws: true })
  })
})
