package com.rush.rushaicodemother.orchestration.create.recipe;

import org.springframework.stereotype.Component;

import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.*;

/** 渲染 Go 存储库层及其列入允许列表的排序表达式。 */
@Component
final class BackendRepositoryTemplate {

    String backendRepository(BackendRecipe recipe) {
        String columns = String.join(", ", recipe.fields().stream().map(field -> snake(field.name())).toList());
        String placeholders = String.join(", ", recipe.fields().stream().map(ignored -> "?").toList());
        String createArgs = String.join(", ", recipe.fields().stream().map(field -> "req." + pascal(field.name())).toList());
        String scanTargets = String.join(", ", recipe.fields().stream().map(field -> "&item." + pascal(field.name())).toList());
        String updateSet = String.join(", ", recipe.fields().stream().map(field -> snake(field.name()) + " = ?").toList());
        String updateArgs = String.join(", ", recipe.fields().stream().map(field -> "req." + pascal(field.name())).toList());
        String searchColumn = recipe.fields().isEmpty() ? "id" : snake(recipe.fields().getFirst().name());
        String updateWhere = recipe.options().softDelete() ? "where id = ? and is_deleted = 0" : "where id = ?";
        String deleteSql = recipe.options().softDelete()
                ? "update " + recipe.tableName() + " set is_deleted = 1, updated_at = current_timestamp where id = ?"
                : "delete from " + recipe.tableName() + " where id = ?";
        String detailWhere = recipe.options().softDelete() ? "where id = ? and is_deleted = 0" : "where id = ?";
        String paginationVars = recipe.options().pagination()
                ? """
                	limit := req.PageSize
                	offset := (req.Current - 1) * req.PageSize
                """
                : "";
        String paginationSql = recipe.options().pagination()
                ? """
                		limit ? offset ?
                """
                : "";
        String queryArgs = recipe.options().pagination() ? "append(args, limit, offset)..." : "args...";
        String softDeleteCondition = recipe.options().softDelete()
                ? "\tconditions := []string{\"is_deleted = 0\"}"
                : "\tconditions := make([]string, 0)";
        String searchCondition = recipe.options().search()
                ? """
                	if strings.TrimSpace(req.Keyword) != "" {
                		conditions = append(conditions, "%s like ?")
                		args = append(args, "%%"+strings.TrimSpace(req.Keyword)+"%%")
                	}
                """.formatted(searchColumn)
                : "";
        String orderBy = recipe.options().sort() ? "safeOrderBy(req)" : "\"order by id desc\"";
        String sortHelper = recipe.options().sort() ? safeOrderByFunction(recipe) : "";
        return """
                package %s

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

                func (r *Repository) Create(req Create%sRequest) (int64, error) {
                	result, err := r.db.Exec(`
                		insert into %s (%s)
                		values (%s)
                	`, %s)
                	if err != nil {
                		return 0, err
                	}
                	return result.LastInsertId()
                }

                func (r *Repository) Update(req Update%sRequest) error {
                	_, err := r.db.Exec(`
                		update %s
                		set %s, updated_at = current_timestamp
                		%s
                	`, %s, req.ID)
                	return err
                }

                func (r *Repository) Delete(id int64) error {
                	_, err := r.db.Exec("%s", id)
                	return err
                }

                func (r *Repository) FindByID(id int64) (%s, error) {
                	return r.scanOne(`
                		select id, %s, created_at, updated_at
                		from %s
                		%s
                	`, id)
                }

                func (r *Repository) List(req QueryRequest) ([]%s, int64, error) {
                	where, args := buildWhere(req)
                	var total int64
                	if err := r.db.QueryRow("select count(*) from %s "+where, args...).Scan(&total); err != nil {
                		return nil, 0, err
                	}
                %s	orderBy := %s
                	rows, err := r.db.Query(`
                		select id, %s, created_at, updated_at
                		from %s `+where+`
                		`+orderBy+`
                %s	`, %s)
                	if err != nil {
                		return nil, 0, err
                	}
                	defer rows.Close()
                	records := make([]%s, 0)
                	for rows.Next() {
                		var item %s
                		if err := rows.Scan(&item.ID, %s, &item.CreatedAt, &item.UpdatedAt); err != nil {
                			return nil, 0, err
                		}
                		records = append(records, item)
                	}
                	return records, total, rows.Err()
                }

                func (r *Repository) scanOne(query string, args ...any) (%s, error) {
                	var item %s
                	err := r.db.QueryRow(query, args...).Scan(&item.ID, %s, &item.CreatedAt, &item.UpdatedAt)
                	return item, err
                }

                func buildWhere(req QueryRequest) (string, []any) {
                %s
                	args := make([]any, 0)
                %s	if len(conditions) == 0 {
                		return "", args
                	}
                	return "where " + strings.Join(conditions, " and "), args
                }
                %s
                """.formatted(
                recipe.packageName(),
                recipe.structName(),
                recipe.tableName(),
                columns,
                placeholders,
                createArgs,
                recipe.structName(),
                recipe.tableName(),
                updateSet,
                updateWhere,
                updateArgs,
                deleteSql,
                recipe.structName(),
                columns,
                recipe.tableName(),
                detailWhere,
                recipe.structName(),
                recipe.tableName(),
                paginationVars,
                orderBy,
                columns,
                recipe.tableName(),
                paginationSql,
                queryArgs,
                recipe.structName(),
                recipe.structName(),
                scanTargets,
                recipe.structName(),
                recipe.structName(),
                scanTargets,
                softDeleteCondition,
                searchCondition,
                sortHelper
        );
    }

    private String safeOrderByFunction(BackendRecipe recipe) {
        String cases = recipe.fields().stream()
                .map(field -> "\tcase \"" + camel(field.name()) + "\":\n\t\tcolumn = \"" + snake(field.name()) + "\"")
                .collect(java.util.stream.Collectors.joining("\n"));
        return """

                func safeOrderBy(req QueryRequest) string {
                	column := "id"
                	switch req.SortBy {
                %s
                	}
                	direction := "desc"
                	if strings.EqualFold(req.SortOrder, "asc") {
                		direction = "asc"
                	}
                	return "order by " + column + " " + direction
                }
                """.formatted(cases);
    }
}
