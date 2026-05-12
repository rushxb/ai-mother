package response

import (
	"encoding/json"
	"net/http"
)

type Response struct {
	Code    int `json:"code"`
	Data    any `json:"data,omitempty"`
	Message string `json:"message"`
}

func OK(w http.ResponseWriter, data any) {
	JSON(w, http.StatusOK, Response{Code: 0, Data: data, Message: "ok"})
}

func Error(w http.ResponseWriter, status int, message string) {
	JSON(w, status, Response{Code: status, Message: message})
}

func JSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
