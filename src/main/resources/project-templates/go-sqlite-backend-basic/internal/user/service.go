package user

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"log/slog"
	"strings"

	"golang.org/x/crypto/bcrypt"
)

// Service 用户服务
type Service struct {
	repo *Repository
}

// NewService 创建用户服务
func NewService(repo *Repository) *Service {
	return &Service{repo: repo}
}

// Register 用户注册
func (s *Service) Register(req RegisterRequest) (int64, error) {
	if len(req.UserAccount) < 4 || len(req.UserPassword) < 8 {
		return 0, errors.New("账号或密码格式错误")
	}
	if req.UserPassword != req.CheckPassword {
		return 0, errors.New("两次输入的密码不一致")
	}
	_, err := s.repo.FindByAccount(req.UserAccount)
	if err == nil {
		return 0, errors.New("账号已存在")
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return 0, err
	}
	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(req.UserPassword), bcrypt.DefaultCost)
	if err != nil {
		slog.Error("hash password failed", "error", err)
		return 0, errors.New("系统异常，请稍后重试")
	}
	id, err := s.repo.Create(User{
		UserAccount:  req.UserAccount,
		UserPassword: string(hashedPassword),
		UserName:     req.UserAccount,
		UserRole:     "user",
	})
	if err != nil {
		slog.Error("create user failed", "error", err, "account", req.UserAccount)
		return 0, errors.New("注册失败，请稍后重试")
	}
	slog.Info("user registered", "id", id, "account", req.UserAccount)
	return id, nil
}

// Login 用户登录
func (s *Service) Login(req LoginRequest) (string, LoginUserVO, error) {
	user, err := s.repo.FindByAccount(req.UserAccount)
	if err != nil || bcrypt.CompareHashAndPassword([]byte(user.UserPassword), []byte(req.UserPassword)) != nil {
		return "", LoginUserVO{}, errors.New("账号或密码错误")
	}
	token, err := randomToken()
	if err != nil {
		slog.Error("generate token failed", "error", err)
		return "", LoginUserVO{}, errors.New("系统异常，请稍后重试")
	}
	if err := s.repo.SaveSession(token, user.ID); err != nil {
		slog.Error("save session failed", "error", err)
		return "", LoginUserVO{}, errors.New("登录失败，请稍后重试")
	}
	slog.Info("user logged in", "id", user.ID, "account", user.UserAccount)
	return token, toLoginUserVO(user), nil
}

// Current 获取当前登录用户
func (s *Service) Current(token string) (LoginUserVO, error) {
	user, err := s.repo.FindBySession(strings.TrimSpace(token))
	if err != nil {
		return LoginUserVO{}, errors.New("未登录")
	}
	return toLoginUserVO(user), nil
}

// Logout 退出登录
func (s *Service) Logout(token string) error {
	return s.repo.DeleteSession(strings.TrimSpace(token))
}

// List 用户列表（分页）
func (s *Service) List(req QueryRequest) (Page[LoginUserVO], error) {
	if req.Current <= 0 {
		req.Current = 1
	}
	if req.PageSize <= 0 || req.PageSize > 100 {
		req.PageSize = 10
	}
	users, total, err := s.repo.List(req)
	if err != nil {
		slog.Error("list users failed", "error", err)
		return Page[LoginUserVO]{}, errors.New("查询失败")
	}
	records := make([]LoginUserVO, 0, len(users))
	for _, item := range users {
		records = append(records, toLoginUserVO(item))
	}
	return Page[LoginUserVO]{Records: records, Total: total, Current: req.Current, PageSize: req.PageSize}, nil
}

func toLoginUserVO(user User) LoginUserVO {
	return LoginUserVO{
		ID:          user.ID,
		UserAccount: user.UserAccount,
		UserName:    user.UserName,
		UserAvatar:  user.UserAvatar,
		UserRole:    user.UserRole,
	}
}

func randomToken() (string, error) {
	bytes := make([]byte, 32)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return hex.EncodeToString(bytes), nil
}
