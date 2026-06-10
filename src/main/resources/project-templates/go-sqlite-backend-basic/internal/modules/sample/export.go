package sample

// ExportRequest is a seed extension point for generated export endpoints.
type ExportRequest struct {
	Keyword string   `json:"keyword"`
	Fields  []string `json:"fields"`
	Format  string   `json:"format"`
}
