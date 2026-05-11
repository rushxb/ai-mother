import request from './request'

export function getHomeFeed() {
  return request.get('/mobile/home')
}

// @AI_INJECT_API
