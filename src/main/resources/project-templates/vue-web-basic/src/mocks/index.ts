import Mock from 'mockjs'

export function setupMock() {
  if (import.meta.env.VITE_USE_MOCK !== 'true') {
    return
  }

  Mock.setup({ timeout: '120-360' })

  Mock.mock(/\/api\/overview$/, 'get', {
    code: 0,
    data: {
      title: 'Mock Overview',
      updatedAt: '@datetime'
    }
  })

  // @AI_INJECT_MOCK
}
