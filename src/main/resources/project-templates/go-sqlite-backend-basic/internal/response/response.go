package response

import (
	"encoding/json"
	"net/http"
)

// ErrorCode 业务错误码
type ErrorCode int

const (
	CodeSuccess       ErrorCode = 0
	CodeBadRequest    ErrorCode = 40000
	CodeUnauthorized  ErrorCode = 40100
	CodeForbidden     ErrorCode = 40300
	CodeNotFound      ErrorCode = 40400
	CodeConflict      ErrorCode = 40900
	CodeInternal      ErrorCode = 50000
	CodeUnavailable   ErrorCode = 50300
)

var codeMessages = map[ErrorCode]string{
	CodeSuccess:      "ok",
	CodeBadRequest:   "请求参数错误",
	CodeUnauthorized: "未登录或登录已过期",
	CodeForbidden:    "无权限访问",
	CodeNotFound:     "资源不存在",
	CodeConflict:     "资源冲突",
	CodeInternal:     "系统内部异常",
	CodeUnavailable:  "服务暂不可用",
}

// Message 返回错误码对应的消息
func (c ErrorCode) Message() string {
	if msg, ok := codeMessages[c]; ok {
		return msg
	}
	return "未知错误"
}

// HTTPStatus 返回对应的 HTTP 状态码
func (c ErrorCode) HTTPStatus() int {
	switch {
	case c == 0:
		return http.StatusOK
	case c >= 40000 && c < 50000:
		return http.StatusBadRequest
	case c >= 50000:
		return http.StatusInternalServerError
	default:
		return http.StatusInternalServerError
	}
}

// Response 统一响应结构
type Response struct {
	Code    ErrorCode `json:"code"`
	Data    any       `json:"data,omitempty"`
	Message string    `json:"message"`
}

// OK 成功响应
func OK(w http.ResponseWriter, data any) {
	JSON(w, http.StatusOK, Response{Code: CodeSuccess, Data: data, Message: CodeSuccess.Message()})
}

// Error 错误响应
func Error(w http.ResponseWriter, code ErrorCode, message string) {
	if message == "" {
		message = code.Message()
	}
	JSON(w, code.HTTPStatus(), Response{Code: code, Message: message})
}

// ErrorWithDetail 带详情的错误响应
func ErrorWithDetail(w http.ResponseWriter, code ErrorCode, detail string) {
	message := code.Message()
	if detail != "" {
		message = detail
	}
	JSON(w, code.HTTPStatus(), Response{Code: code, Message: message})
}

// JSON 通用 JSON 响应
func JSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
