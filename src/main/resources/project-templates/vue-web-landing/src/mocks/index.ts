import Mock from 'mockjs'

export function setupMock() {
  if (import.meta.env.VITE_USE_MOCK !== 'true') {
    return
  }

  Mock.setup({ timeout: '120-360' })
}
