import request from './request'

export function getLandingMeta() {
  return request.get('/landing/meta')
}

// @AI_INJECT_API
