import request from './request'

export function getOverview() {
  return request.get('/overview')
}

// @AI_INJECT_API
