package domain

import "time"

// EntityMeta keeps common persistence fields aligned across generated modules.
type EntityMeta struct {
	ID        int64     `json:"id"`
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

// PageRequest is the common pagination request shape used by frontend and backend.
type PageRequest struct {
	Current  int `json:"current" validate:"gte=1"`
	PageSize int `json:"pageSize" validate:"gte=1,lte=100"`
}

// Normalize applies safe pagination defaults.
func (r *PageRequest) Normalize() {
	if r.Current <= 0 {
		r.Current = 1
	}
	if r.PageSize <= 0 || r.PageSize > 100 {
		r.PageSize = 10
	}
}

// Page is the shared pagination response shape.
type Page[T any] struct {
	Records  []T   `json:"records"`
	Total    int64 `json:"total"`
	Current  int   `json:"current"`
	PageSize int   `json:"pageSize"`
}
