package sample_test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"backend-template/internal/modules/sample"
	"backend-template/internal/response"
)

func setupTestHandler(t *testing.T) *sample.Handler {
	t.Helper()
	repo := sample.NewRepository(nil)
	service := sample.NewService(repo)
	return sample.NewHandler(service)
}

func TestRegister_InvalidRequest(t *testing.T) {
	handler := setupTestHandler(t)
	mux := http.NewServeMux()
	handler.RegisterRoutes(mux)

	tests := []struct {
		name       string
		body       any
		wantStatus int
	}{
		{name: "empty body", body: nil, wantStatus: http.StatusBadRequest},
		{
			name: "short account",
			body: sample.RegisterRequest{
				UserAccount:   "ab",
				UserPassword:  "12345678",
				CheckPassword: "12345678",
			},
			wantStatus: http.StatusBadRequest,
		},
		{
			name: "short password",
			body: sample.RegisterRequest{
				UserAccount:   "testuser",
				UserPassword:  "123",
				CheckPassword: "123",
			},
			wantStatus: http.StatusBadRequest,
		},
		{
			name: "password mismatch",
			body: sample.RegisterRequest{
				UserAccount:   "testuser",
				UserPassword:  "12345678",
				CheckPassword: "87654321",
			},
			wantStatus: http.StatusBadRequest,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var body bytes.Buffer
			if tt.body != nil {
				_ = json.NewEncoder(&body).Encode(tt.body)
			}
			req := httptest.NewRequest("POST", "/api/user/register", &body)
			req.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()

			mux.ServeHTTP(w, req)

			if w.Code != tt.wantStatus {
				t.Errorf("got status %d, want %d", w.Code, tt.wantStatus)
			}

			var resp response.Response
			if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
				t.Fatalf("decode response: %v", err)
			}
			if resp.Code == 0 {
				t.Error("expected error code, got success")
			}
		})
	}
}
