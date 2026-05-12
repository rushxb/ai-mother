package user

import (
	"encoding/json"
	"net/http"
	"strings"

	"backend-template/internal/response"
)

type Handler struct {
	service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{service: service}
}

func (h *Handler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/user/register", h.register)
	mux.HandleFunc("POST /api/user/login", h.login)
	mux.HandleFunc("POST /api/user/logout", h.logout)
	mux.HandleFunc("GET /api/user/current", h.current)
	mux.HandleFunc("POST /api/user/list/page", h.list)
}

func (h *Handler) register(w http.ResponseWriter, r *http.Request) {
	var req RegisterRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		response.Error(w, http.StatusBadRequest, "请求参数错误")
		return
	}
	id, err := h.service.Register(req)
	if err != nil {
		response.Error(w, http.StatusBadRequest, err.Error())
		return
	}
	response.OK(w, id)
}

func (h *Handler) login(w http.ResponseWriter, r *http.Request) {
	var req LoginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		response.Error(w, http.StatusBadRequest, "请求参数错误")
		return
	}
	token, user, err := h.service.Login(req)
	if err != nil {
		response.Error(w, http.StatusBadRequest, err.Error())
		return
	}
	response.OK(w, map[string]any{"token": token, "user": user})
}

func (h *Handler) logout(w http.ResponseWriter, r *http.Request) {
	if err := h.service.Logout(bearerToken(r)); err != nil {
		response.Error(w, http.StatusInternalServerError, "退出登录失败")
		return
	}
	response.OK(w, true)
}

func (h *Handler) current(w http.ResponseWriter, r *http.Request) {
	user, err := h.service.Current(bearerToken(r))
	if err != nil {
		response.Error(w, http.StatusUnauthorized, err.Error())
		return
	}
	response.OK(w, user)
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
	currentUser, err := h.service.Current(bearerToken(r))
	if err != nil {
		response.Error(w, http.StatusUnauthorized, err.Error())
		return
	}
	if currentUser.UserRole != "admin" {
		response.Error(w, http.StatusForbidden, "无权限")
		return
	}
	var req QueryRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		response.Error(w, http.StatusBadRequest, "请求参数错误")
		return
	}
	page, err := h.service.List(req)
	if err != nil {
		response.Error(w, http.StatusInternalServerError, "查询失败")
		return
	}
	response.OK(w, page)
}

func bearerToken(r *http.Request) string {
	value := strings.TrimSpace(r.Header.Get("Authorization"))
	return strings.TrimSpace(strings.TrimPrefix(value, "Bearer "))
}
