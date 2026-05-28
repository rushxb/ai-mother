package sample

import (
	"database/sql"
	"strings"
)

type Repository struct {
	db *sql.DB
}

func NewRepository(db *sql.DB) *Repository {
	return &Repository{db: db}
}

func (r *Repository) Create(user User) (int64, error) {
	result, err := r.db.Exec(`
		insert into users (user_account, user_password, user_name, user_avatar, user_role)
		values (?, ?, ?, ?, ?)
	`, user.UserAccount, user.UserPassword, user.UserName, user.UserAvatar, user.UserRole)
	if err != nil {
		return 0, err
	}
	return result.LastInsertId()
}

func (r *Repository) FindByAccount(account string) (User, error) {
	return r.scanOne(`
		select id, user_account, user_password, user_name, user_avatar, user_role, created_at, updated_at
		from users
		where user_account = ? and is_deleted = 0
	`, account)
}

func (r *Repository) FindBySession(token string) (User, error) {
	return r.scanOne(`
		select u.id, u.user_account, u.user_password, u.user_name, u.user_avatar, u.user_role, u.created_at, u.updated_at
		from sessions s
		join users u on u.id = s.user_id
		where s.token = ? and u.is_deleted = 0
	`, token)
}

func (r *Repository) SaveSession(token string, userID int64) error {
	_, err := r.db.Exec(`
		insert into sessions (token, user_id)
		values (?, ?)
		on conflict(token) do update set user_id = excluded.user_id, created_at = current_timestamp
	`, token, userID)
	return err
}

func (r *Repository) DeleteSession(token string) error {
	_, err := r.db.Exec("delete from sessions where token = ?", token)
	return err
}

func (r *Repository) List(req QueryRequest) ([]User, int64, error) {
	where, args := buildWhere(req)
	var total int64
	if err := r.db.QueryRow("select count(*) from users "+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	limit := req.PageSize
	offset := (req.Current - 1) * req.PageSize
	rows, err := r.db.Query(`
		select id, user_account, user_password, user_name, user_avatar, user_role, created_at, updated_at
		from users `+where+`
		order by id desc
		limit ? offset ?
	`, append(args, limit, offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	users := make([]User, 0)
	for rows.Next() {
		var user User
		if err := rows.Scan(&user.ID, &user.UserAccount, &user.UserPassword, &user.UserName, &user.UserAvatar, &user.UserRole, &user.CreatedAt, &user.UpdatedAt); err != nil {
			return nil, 0, err
		}
		users = append(users, user)
	}
	return users, total, rows.Err()
}

func (r *Repository) scanOne(query string, args ...any) (User, error) {
	var user User
	err := r.db.QueryRow(query, args...).Scan(&user.ID, &user.UserAccount, &user.UserPassword, &user.UserName, &user.UserAvatar, &user.UserRole, &user.CreatedAt, &user.UpdatedAt)
	return user, err
}

func buildWhere(req QueryRequest) (string, []any) {
	conditions := []string{"is_deleted = 0"}
	args := make([]any, 0)
	if strings.TrimSpace(req.UserAccount) != "" {
		conditions = append(conditions, "user_account like ?")
		args = append(args, "%"+strings.TrimSpace(req.UserAccount)+"%")
	}
	if strings.TrimSpace(req.UserName) != "" {
		conditions = append(conditions, "user_name like ?")
		args = append(args, "%"+strings.TrimSpace(req.UserName)+"%")
	}
	if strings.TrimSpace(req.UserRole) != "" {
		conditions = append(conditions, "user_role = ?")
		args = append(args, strings.TrimSpace(req.UserRole))
	}
	return "where " + strings.Join(conditions, " and "), args
}
