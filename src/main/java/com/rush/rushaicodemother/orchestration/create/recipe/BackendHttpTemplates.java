package com.rush.rushaicodemother.orchestration.create.recipe;

import org.springframework.stereotype.Component;

/** 呈现 Go 后端模块的 HTTP 处理程序和服务器组合。 */
@Component
final class BackendHttpTemplates {

    /** 返回后端处理器。 */
    String backendHandler(BackendRecipe recipe) {
        CrudApiContract apiContract = CrudApiContract.fromTable(
                recipe.tableName(), recipe.options().pagination());
        String route = apiContract.collectionPath();
        String optionalRoutes = ""
                + (recipe.options().batchActions() ? "\tmux.HandleFunc(\"POST /api" + route + "/batch-delete\", h.batchDelete)\n" : "")
                + (recipe.options().importExport() ? "\tmux.HandleFunc(\"POST /api" + route + "/import\", h.importItems)\n\tmux.HandleFunc(\"POST /api" + route + "/export\", h.exportItems)\n" : "");
        String authGuard = recipe.options().authRequired() ? """
                	if !requireAuth(w, r) {
                		return
                	}
                """ : "";
        String optionalHandlers = ""
                + (recipe.options().batchActions() ? batchDeleteHandler() : "")
                + (recipe.options().importExport() ? importExportHandlers(recipe) : "")
                + (recipe.options().authRequired() ? authHelper() : "");
        return """
                package %s

                import (
                	"encoding/json"
                	"net/http"
                	"strconv"
                	"strings"

                	"backend-template/internal/response"
                	"backend-template/internal/validator"
                )

                type Handler struct {
                	service *Service
                }

                func NewHandler(service *Service) *Handler {
                	return &Handler{service: service}
                }

                func (h *Handler) RegisterRoutes(mux *http.ServeMux) {
                	mux.HandleFunc("POST /api%s", h.create)
                	mux.HandleFunc("PUT /api%s", h.update)
                	mux.HandleFunc("DELETE /api%s/", h.delete)
                	mux.HandleFunc("GET /api%s/", h.detail)
                \tmux.HandleFunc("POST /api%s", h.list)
                %s
                	// @AI_INJECT_ROUTE: %s
                }

                func (h *Handler) create(w http.ResponseWriter, r *http.Request) {
                %s
                	var req Create%sRequest
                	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                		response.Error(w, response.CodeBadRequest, "请求参数格式错误")
                		return
                	}
                	if err := validator.ValidateStruct(req); err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	id, err := h.service.Create(req)
                	if err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	response.OK(w, id)
                }

                func (h *Handler) update(w http.ResponseWriter, r *http.Request) {
                %s
                	var req Update%sRequest
                	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                		response.Error(w, response.CodeBadRequest, "请求参数格式错误")
                		return
                	}
                	if err := validator.ValidateStruct(req); err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	if err := h.service.Update(req); err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	response.OK(w, true)
                }

                func (h *Handler) delete(w http.ResponseWriter, r *http.Request) {
                %s
                	id, err := parseID(r.URL.Path)
                	if err != nil {
                		response.Error(w, response.CodeBadRequest, "记录 ID 格式错误")
                		return
                	}
                	if err := h.service.Delete(id); err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	response.OK(w, true)
                }

                func (h *Handler) detail(w http.ResponseWriter, r *http.Request) {
                %s
                	id, err := parseID(r.URL.Path)
                	if err != nil {
                		response.Error(w, response.CodeBadRequest, "记录 ID 格式错误")
                		return
                	}
                	item, err := h.service.Detail(id)
                	if err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	response.OK(w, item)
                }

                func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
                %s
                	var req QueryRequest
                	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                		response.Error(w, response.CodeBadRequest, "请求参数格式错误")
                		return
                	}
                	if err := validator.ValidateStruct(req); err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	page, err := h.service.List(req)
                	if err != nil {
                		response.Error(w, response.CodeInternal, err.Error())
                		return
                	}
                	response.OK(w, page)
                }
                %s

                func parseID(path string) (int64, error) {
                	parts := strings.Split(strings.Trim(path, "/"), "/")
                	return strconv.ParseInt(parts[len(parts)-1], 10, 64)
                }
                """.formatted(
                recipe.packageName(),
                route,
                route,
                route,
                route,
                apiContract.listPath(),
                optionalRoutes,
                recipe.packageName(),
                authGuard,
                recipe.structName(),
                authGuard,
                recipe.structName(),
                authGuard,
                authGuard,
                authGuard,
                optionalHandlers
        );
    }

    /** 返回后端{@code Wiring}。 */
    String backendWiring(BackendRecipe recipe) {
        String varPrefix = recipe.packageName();
        return """
                	%sRepo := %s.NewRepository(db)
                	%sService := %s.NewService(%sRepo)
                	%sHandler := %s.NewHandler(%sService)
                	%sHandler.RegisterRoutes(mux)
                """.formatted(
                varPrefix,
                recipe.packageName(),
                varPrefix,
                recipe.packageName(),
                varPrefix,
                varPrefix,
                recipe.packageName(),
                varPrefix,
                varPrefix
        );
    }

    /** 返回批次删除处理器。 */
    private String batchDeleteHandler() {
        return """

                func (h *Handler) batchDelete(w http.ResponseWriter, r *http.Request) {
                	var req BatchDeleteRequest
                	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                		response.Error(w, response.CodeBadRequest, "请求参数格式错误")
                		return
                	}
                	if err := validator.ValidateStruct(req); err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	if err := h.service.BatchDelete(req.IDs); err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	response.OK(w, true)
                }
                """;
    }

    /** 返回导入导出处理器。 */
    private String importExportHandlers(BackendRecipe recipe) {
        return """

                func (h *Handler) exportItems(w http.ResponseWriter, r *http.Request) {
                	var req QueryRequest
                	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                		response.Error(w, response.CodeBadRequest, "请求参数格式错误")
                		return
                	}
                	items, err := h.service.Export(req)
                	if err != nil {
                		response.Error(w, response.CodeInternal, err.Error())
                		return
                	}
                	response.OK(w, items)
                }

                func (h *Handler) importItems(w http.ResponseWriter, r *http.Request) {
                	var req []Create%sRequest
                	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                		response.Error(w, response.CodeBadRequest, "请求参数格式错误")
                		return
                	}
                	count, err := h.service.Import(req)
                	if err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	response.OK(w, map[string]any{"count": count})
                }
                """.formatted(recipe.structName());
    }

    /** 返回{@code auth}{@code Helper}。 */
    private String authHelper() {
        return """

                func requireAuth(w http.ResponseWriter, r *http.Request) bool {
                	token := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
                	if token == "" {
                		response.Error(w, response.CodeUnauthorized, "未登录")
                		return false
                	}
                	return true
                }
                """;
    }
}
