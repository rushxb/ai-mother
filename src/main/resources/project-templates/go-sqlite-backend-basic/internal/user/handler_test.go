package user_test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"backend-template/internal/response"
	"backend-template/internal/user"
)

func setupTestHandler(t *testing.T) *user.Handler {
	t.Helper()
	// 注意：实际测试需要使用测试数据库
	// 这里仅展示测试结构
	repo := user.NewRepository(nil) // 测试时传入测试数据库
	service := user.NewService(repo)
	return user.NewHandler(service)
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
		{
			name:       "empty body",
			body:       nil,
			wantStatus: http.StatusBadRequest,
		},
		{
			name: "short account",
			body: user.RegisterRequest{
				UserAccount:   "ab",
				UserPassword:  "12345678",
				CheckPassword: "12345678",
			},
			wantStatus: http.StatusBadRequest,
		},
		{
			name: "short password",
			body: user.RegisterRequest{
				UserAccount:   "testuser",
				UserPassword:  "123",
				CheckPassword: "123",
			},
			wantStatus: http.StatusBadRequest,
		},
		{
			name: "password mismatch",
			body: user.RegisterRequest{
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

func TestLogin_InvalidRequest(t *testing.T) {
	handler := setupTestHandler(t)
	mux := http.NewServeMux()
	handler.RegisterRoutes(mux)

	tests := []struct {
		name       string
		body       any
		wantStatus int
	}{
		{
			name: "empty account",
			body: user.LoginRequest{
				UserAccount:  "",
				UserPassword: "12345678",
			},
			wantStatus: http.StatusBadRequest,
		},
		{
			name: "empty password",
			body: user.LoginRequest{
				UserAccount:  "testuser",
				UserPassword: "",
			},
			wantStatus: http.StatusBadRequest,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var body bytes.Buffer
			_ = json.NewEncoder(&body).Encode(tt.body)
			req := httptest.NewRequest("POST", "/api/user/login", &body)
			req.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()

			mux.ServeHTTP(w, req)

			if w.Code != tt.wantStatus {
				t.Errorf("got status %d, want %d", w.Code, tt.wantStatus)
			}
		})
	}
}

func TestCurrent_NoToken(t *testing.T) {
	handler := setupTestHandler(t)
	mux := http.NewServeMux()
	handler.RegisterRoutes(mux)

	req := httptest.NewRequest("GET", "/api/user/current", nil)
	w := httptest.NewRecorder()

	mux.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("got status %d, want %d", w.Code, http.StatusUnauthorized)
	}
}
