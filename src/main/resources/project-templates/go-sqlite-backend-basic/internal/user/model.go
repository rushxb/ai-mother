package user

import "time"

// User 用户实体
type User struct {
	ID           int64     `json:"id"`
	UserAccount  string    `json:"userAccount"`
	UserPassword string    `json:"-"`
	UserName     string    `json:"userName"`
	UserAvatar   string    `json:"userAvatar,omitempty"`
	UserRole     string    `json:"userRole"`
	CreatedAt    time.Time `json:"createdAt"`
	UpdatedAt    time.Time `json:"updatedAt"`
}

// RegisterRequest 注册请求
type RegisterRequest struct {
	UserAccount   string `json:"userAccount" validate:"required,min=4,max=32"`
	UserPassword  string `json:"userPassword" validate:"required,min=8,max=64"`
	CheckPassword string `json:"checkPassword" validate:"required"`
}

// LoginRequest 登录请求
type LoginRequest struct {
	UserAccount  string `json:"userAccount" validate:"required"`
	UserPassword string `json:"userPassword" validate:"required"`
}

// QueryRequest 查询请求
type QueryRequest struct {
	Current     int    `json:"current" validate:"gte=1"`
	PageSize    int    `json:"pageSize" validate:"gte=1,lte=100"`
	UserAccount string `json:"userAccount"`
	UserName    string `json:"userName"`
	UserRole    string `json:"userRole" validate:"omitempty,oneof=user admin"`
}

// LoginUserVO 登录用户视图对象
type LoginUserVO struct {
	ID          int64  `json:"id"`
	UserAccount string `json:"userAccount"`
	UserName    string `json:"userName"`
	UserAvatar  string `json:"userAvatar,omitempty"`
	UserRole    string `json:"userRole"`
}

// Page 分页结果
type Page[T any] struct {
	Records  []T   `json:"records"`
	Total    int64 `json:"total"`
	Current  int   `json:"current"`
	PageSize int   `json:"pageSize"`
}
