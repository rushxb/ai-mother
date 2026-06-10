package sample

import "time"

// AuditLog is a seed extension point for generated audit/history features.
type AuditLog struct {
	ID        int64     `json:"id"`
	Action    string    `json:"action"`
	Operator  string    `json:"operator"`
	TargetID  int64     `json:"targetId"`
	CreatedAt time.Time `json:"createdAt"`
}
