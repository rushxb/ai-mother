package response_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"backend-template/internal/response"
)

func TestOK(t *testing.T) {
	w := httptest.NewRecorder()
	data := map[string]string{"key": "value"}

	response.OK(w, data)

	if w.Code != http.StatusOK {
		t.Errorf("got status %d, want %d", w.Code, http.StatusOK)
	}

	var resp response.Response
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}

	if resp.Code != response.CodeSuccess {
		t.Errorf("got code %d, want %d", resp.Code, response.CodeSuccess)
	}
	if resp.Message != "ok" {
		t.Errorf("got message %q, want %q", resp.Message, "ok")
	}
}

func TestError(t *testing.T) {
	w := httptest.NewRecorder()

	response.Error(w, response.CodeBadRequest, "参数错误")

	if w.Code != http.StatusBadRequest {
		t.Errorf("got status %d, want %d", w.Code, http.StatusBadRequest)
	}

	var resp response.Response
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}

	if resp.Code != response.CodeBadRequest {
		t.Errorf("got code %d, want %d", resp.Code, response.CodeBadRequest)
	}
	if resp.Message != "参数错误" {
		t.Errorf("got message %q, want %q", resp.Message, "参数错误")
	}
}

func TestErrorWithDefaultMessage(t *testing.T) {
	w := httptest.NewRecorder()

	response.Error(w, response.CodeUnauthorized, "")

	var resp response.Response
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}

	if resp.Message != response.CodeUnauthorized.Message() {
		t.Errorf("got message %q, want %q", resp.Message, response.CodeUnauthorized.Message())
	}
}

func TestErrorCode_HTTPStatus(t *testing.T) {
	tests := []struct {
		code response.ErrorCode
		want int
	}{
		{response.CodeSuccess, http.StatusOK},
		{response.CodeBadRequest, http.StatusBadRequest},
		{response.CodeUnauthorized, http.StatusBadRequest},
		{response.CodeForbidden, http.StatusBadRequest},
		{response.CodeNotFound, http.StatusBadRequest},
		{response.CodeInternal, http.StatusInternalServerError},
	}

	for _, tt := range tests {
		t.Run(tt.code.Message(), func(t *testing.T) {
			if got := tt.code.HTTPStatus(); got != tt.want {
				t.Errorf("got %d, want %d", got, tt.want)
			}
		})
	}
}
