package user

import "time"

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

type RegisterRequest struct {
	UserAccount  string `json:"userAccount"`
	UserPassword string `json:"userPassword"`
	CheckPassword string `json:"checkPassword"`
}

type LoginRequest struct {
	UserAccount  string `json:"userAccount"`
	UserPassword string `json:"userPassword"`
}

type QueryRequest struct {
	Current     int    `json:"current"`
	PageSize    int    `json:"pageSize"`
	UserAccount string `json:"userAccount"`
	UserName    string `json:"userName"`
	UserRole    string `json:"userRole"`
}

type LoginUserVO struct {
	ID          int64  `json:"id"`
	UserAccount string `json:"userAccount"`
	UserName    string `json:"userName"`
	UserAvatar  string `json:"userAvatar,omitempty"`
	UserRole    string `json:"userRole"`
}

type Page[T any] struct {
	Records []T   `json:"records"`
	Total   int64 `json:"total"`
	Current int   `json:"current"`
	PageSize int   `json:"pageSize"`
}
