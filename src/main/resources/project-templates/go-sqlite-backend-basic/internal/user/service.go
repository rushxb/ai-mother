package user

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"strings"

	"golang.org/x/crypto/bcrypt"
)

type Service struct {
	repo *Repository
}

func NewService(repo *Repository) *Service {
	return &Service{repo: repo}
}

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
		return 0, err
	}
	return s.repo.Create(User{
		UserAccount:  req.UserAccount,
		UserPassword: string(hashedPassword),
		UserName:     req.UserAccount,
		UserRole:     "user",
	})
}

func (s *Service) Login(req LoginRequest) (string, LoginUserVO, error) {
	user, err := s.repo.FindByAccount(req.UserAccount)
	if err != nil || bcrypt.CompareHashAndPassword([]byte(user.UserPassword), []byte(req.UserPassword)) != nil {
		return "", LoginUserVO{}, errors.New("账号或密码错误")
	}
	token, err := randomToken()
	if err != nil {
		return "", LoginUserVO{}, err
	}
	return token, toLoginUserVO(user), s.repo.SaveSession(token, user.ID)
}

func (s *Service) Current(token string) (LoginUserVO, error) {
	user, err := s.repo.FindBySession(strings.TrimSpace(token))
	if err != nil {
		return LoginUserVO{}, errors.New("未登录")
	}
	return toLoginUserVO(user), nil
}

func (s *Service) Logout(token string) error {
	return s.repo.DeleteSession(strings.TrimSpace(token))
}

func (s *Service) List(req QueryRequest) (Page[LoginUserVO], error) {
	if req.Current <= 0 {
		req.Current = 1
	}
	if req.PageSize <= 0 || req.PageSize > 100 {
		req.PageSize = 10
	}
	users, total, err := s.repo.List(req)
	if err != nil {
		return Page[LoginUserVO]{}, err
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
