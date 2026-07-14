package com.rush.rushaicodemother.orchestration.create.recipe;

import org.springframework.stereotype.Component;

import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeSpecSupport.*;
import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.*;

/** Renders Go domain/model declarations and SQLite schema changes. */
@Component
final class BackendDomainTemplates {

    String domainContract(BackendRecipe recipe) {
        return """
                package domain

                type PageRequest struct {
                	Current  int `json:"current"`
                	PageSize int `json:"pageSize"`
                }

                func (p *PageRequest) Normalize() {
                	if p.Current <= 0 {
                		p.Current = 1
                	}
                	if p.PageSize <= 0 || p.PageSize > 100 {
                		p.PageSize = 10
                	}
                }

                type Page[T any] struct {
                	Records  []T   `json:"records"`
                	Total    int64 `json:"total"`
                	Current  int   `json:"current"`
                	PageSize int   `json:"pageSize"`
                }
                """;
    }

    String backendModel(BackendRecipe recipe) {
        StringBuilder fields = new StringBuilder();
        StringBuilder createFields = new StringBuilder();
        StringBuilder updateFields = new StringBuilder();
        StringBuilder voFields = new StringBuilder();
        for (RecipeField field : recipe.fields()) {
            String goName = pascal(field.name());
            String json = camel(field.name());
            String goType = goType(field.type());
            fields.append("\t").append(goName).append(" ").append(goType).append(" `json:\"").append(json).append("\"`\n");
            createFields.append("\t").append(goName).append(" ").append(goType).append(" `json:\"").append(json).append("\"");
            if (recipe.options().validationRequired() && field.required()) {
                createFields.append(" validate:\"required\"");
            }
            createFields.append("`\n");
            updateFields.append("\t").append(goName).append(" ").append(goType).append(" `json:\"").append(json).append("\"`\n");
            voFields.append("\t").append(goName).append(" ").append(goType).append(" `json:\"").append(json).append("\"`\n");
        }
        String imports = recipe.options().pagination()
                ? """
                import (
                	"time"

                	"backend-template/internal/domain"
                )
                """
                : """
                import "time"
                """;
        String pageRequest = recipe.options().pagination() ? "\tdomain.PageRequest\n" : "";
        String keyword = recipe.options().search() ? "\tKeyword string `json:\"keyword\"`\n" : "";
        String sortFields = recipe.options().sort()
                ? "\tSortBy string `json:\"sortBy\"`\n\tSortOrder string `json:\"sortOrder\"`\n"
                : "";
        String batchRequest = recipe.options().batchActions()
                ? """

                type BatchDeleteRequest struct {
                	IDs []int64 `json:"ids" validate:"required"`
                }
                """
                : "";
        return """
                package %s

                %s

                type %s struct {
                	ID int64 `json:"id"`
                %s	CreatedAt time.Time `json:"createdAt"`
                	UpdatedAt time.Time `json:"updatedAt"`
                }

                type Create%sRequest struct {
                %s}

                type Update%sRequest struct {
                	ID int64 `json:"id"%s`
                %s}

                type QueryRequest struct {
                %s%s%s
                	Status string `json:"status"`
                }
                %s

                type %sVO struct {
                	ID int64 `json:"id"`
                %s	CreatedAt time.Time `json:"createdAt"`
                	UpdatedAt time.Time `json:"updatedAt"`
                }
                """.formatted(
                recipe.packageName(),
                imports.stripTrailing(),
                recipe.structName(),
                fields,
                recipe.structName(),
                createFields,
                recipe.structName(),
                recipe.options().validationRequired() ? " validate:\"required\"" : "",
                updateFields,
                pageRequest,
                keyword,
                sortFields,
                batchRequest,
                recipe.structName(),
                voFields
        );
    }

    String backendSchema(BackendRecipe recipe) {
        StringBuilder fields = new StringBuilder();
        for (RecipeField field : recipe.fields()) {
            fields.append("    ").append(snake(field.name())).append(" ").append(sqlType(field.type()));
            fields.append(sqlDefault(field.type()));
            if (field.required()) {
                fields.append(" not null");
            }
            fields.append(",\n");
        }
        String softDeleteColumn = recipe.options().softDelete()
                ? "    is_deleted integer default 0 not null\n"
                : "    deleted_at timestamp\n";
        String indexes = recipe.indexes().stream()
                .map(column -> "create index if not exists idx_" + recipe.tableName() + "_" + column + " on " + recipe.tableName() + " (" + column + ");")
                .collect(java.util.stream.Collectors.joining("\n"));
        return """

                create table if not exists %s
                (
                    id integer primary key autoincrement,
                %s    created_at timestamp default current_timestamp not null,
                    updated_at timestamp default current_timestamp not null,
                %s
                );

                %s
                """.formatted(recipe.tableName(), fields, softDeleteColumn, indexes);
    }
}
