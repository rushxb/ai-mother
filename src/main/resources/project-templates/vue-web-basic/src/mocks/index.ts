import Mock from 'mockjs'

export function setupMock() {
  if (import.meta.env.VITE_USE_MOCK !== 'true') {
    return
  }

  Mock.setup({ timeout: '120-360' })

  // Auth APIs
  Mock.mock(/\/api\/auth\/login$/, 'post', {
    code: 0,
    data: {
      token: '@guid',
      user: {
        id: '@increment',
        userAccount: 'admin',
        userName: '管理员',
        userAvatar: '',
        userRole: 'admin',
        createTime: '@datetime'
      }
    },
    message: '登录成功'
  })

  Mock.mock(/\/api\/auth\/userInfo$/, 'get', {
    code: 0,
    data: {
      id: 1,
      userAccount: 'admin',
      userName: '管理员',
      userAvatar: '',
      userRole: 'admin',
      createTime: '2024-01-01 00:00:00'
    }
  })

  Mock.mock(/\/api\/auth\/logout$/, 'post', {
    code: 0,
    data: null,
    message: '退出成功'
  })

  // User APIs
  Mock.mock(/\/api\/user\/list$/, 'get', {
    code: 0,
    data: {
      'list|10': [{
        'id|+1': 1,
        userAccount: '@word(4, 8)',
        userName: '@cname',
        userAvatar: '',
        'userRole|1': ['user', 'admin'],
        createTime: '@datetime'
      }],
      total: 100,
      page: 1,
      pageSize: 10
    }
  })

  Mock.mock(/\/api\/overview$/, 'get', {
    code: 0,
    data: {
      title: 'Mock Overview',
      updatedAt: '@datetime'
    }
  })

  // @AI_INJECT_MOCK
}
