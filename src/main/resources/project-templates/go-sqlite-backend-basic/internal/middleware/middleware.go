package middleware

import "net/http"

// Middleware HTTP 中间件类型
type Middleware func(http.Handler) http.Handler

// Chain 组装多个中间件，按顺序执行（第一个中间件最先执行）
func Chain(handler http.Handler, middlewares ...Middleware) http.Handler {
	for i := len(middlewares) - 1; i >= 0; i-- {
		handler = middlewares[i](handler)
	}
	return handler
}
