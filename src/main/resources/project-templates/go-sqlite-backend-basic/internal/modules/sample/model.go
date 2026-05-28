package sample

import (
	"time"

	"backend-template/internal/domain"
)

// User is the sample account entity. Generated projects can replace this module.
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
	UserAccount   string `json:"userAccount" validate:"required,min=4,max=32"`
	UserPassword  string `json:"userPassword" validate:"required,min=8,max=64"`
	CheckPassword string `json:"checkPassword" validate:"required"`
}

type LoginRequest struct {
	UserAccount  string `json:"userAccount" validate:"required"`
	UserPassword string `json:"userPassword" validate:"required"`
}

type QueryRequest struct {
	domain.PageRequest
	UserAccount string `json:"userAccount"`
	UserName    string `json:"userName"`
	UserRole    string `json:"userRole" validate:"omitempty,oneof=user admin"`
}

type LoginUserVO struct {
	ID          int64  `json:"id"`
	UserAccount string `json:"userAccount"`
	UserName    string `json:"userName"`
	UserAvatar  string `json:"userAvatar,omitempty"`
	UserRole    string `json:"userRole"`
}
