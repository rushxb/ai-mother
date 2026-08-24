import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  base: './',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) {
            return undefined
          }
          if (id.includes('ant-design-vue') || id.includes('@ant-design/icons-vue')) {
            return 'vendor-antd'
          }
          if (
            id.includes('/vue/') ||
            id.includes('/vue-router/') ||
            id.includes('/pinia/') ||
            id.includes('/@vueuse/')
          ) {
            return 'vendor-vue'
          }
          if (id.includes('/three/') || id.includes('/motion-v/')) {
            return 'vendor-visual'
          }
          if (id.includes('/markdown-it/') || id.includes('/highlight.js/')) {
            return 'vendor-markdown'
          }
          return 'vendor-common'
        },
      },
    },
  },
  server: {
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: 'http://localhost:8123',
        changeOrigin: true,
        secure: false,
        // Preview 的 Vite HMR 与 HTTP 资源共用 /api 公开代理路径。
        ws: true,
      },
    },
  },
})
