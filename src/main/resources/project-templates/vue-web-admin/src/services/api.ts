import request from './request'

export function getDashboard() {
  return request.get('/dashboard')
}

// @AI_INJECT_API
