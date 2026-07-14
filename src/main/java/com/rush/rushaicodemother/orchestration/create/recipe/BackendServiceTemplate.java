package com.rush.rushaicodemother.orchestration.create.recipe;

import org.springframework.stereotype.Component;

/** Renders the Go application service layer. */
@Component
final class BackendServiceTemplate {

    String backendService(BackendRecipe recipe) {
        String imports = recipe.options().pagination()
                ? """
                import (
                	"errors"
                	"log/slog"

                	"backend-template/internal/domain"
                )
                """
                : """
                import (
                	"errors"
                	"log/slog"
                )
                """;
        String listReturnType = recipe.options().pagination()
                ? "domain.Page[" + recipe.structName() + "VO]"
                : "[]" + recipe.structName() + "VO";
        String normalizeLine = recipe.options().pagination() ? "\treq.Normalize()\n" : "";
        String listErrorReturn = recipe.options().pagination()
                ? "domain.Page[" + recipe.structName() + "VO]{}"
                : "nil";
        String listSuccessReturn = recipe.options().pagination()
                ? "domain.Page[" + recipe.structName() + "VO]{Records: vos, Total: total, Current: req.Current, PageSize: req.PageSize}"
                : "vos";
        String batchDelete = recipe.options().batchActions()
                ? """

                func (s *Service) BatchDelete(ids []int64) error {
                	if len(ids) == 0 {
                		return errors.New("记录 ID 不能为空")
                	}
                	for _, id := range ids {
                		if err := s.Delete(id); err != nil {
                			return err
                		}
                	}
                	return nil
                }
                """
                : "";
        String importExport = recipe.options().importExport()
                ? """

                func (s *Service) Export(req QueryRequest) ([]%sVO, error) {
                	records, _, err := s.repo.List(req)
                	if err != nil {
                		return nil, errors.New("导出%s失败")
                	}
                	vos := make([]%sVO, 0, len(records))
                	for _, item := range records {
                		vos = append(vos, toVO(item))
                	}
                	return vos, nil
                }

                func (s *Service) Import(items []Create%sRequest) (int, error) {
                	count := 0
                	for _, item := range items {
                		if _, err := s.Create(item); err != nil {
                			return count, err
                		}
                		count++
                	}
                	return count, nil
                }
                """.formatted(recipe.structName(), recipe.label(), recipe.structName(), recipe.structName())
                : "";
        return """
                package %s

                %s

                type Service struct {
                	repo *Repository
                }

                func NewService(repo *Repository) *Service {
                	return &Service{repo: repo}
                }

                func (s *Service) Create(req Create%sRequest) (int64, error) {
                	id, err := s.repo.Create(req)
                	if err != nil {
                		slog.Error("create %s failed", "error", err)
                		return 0, errors.New("创建%s失败")
                	}
                	return id, nil
                }

                func (s *Service) Update(req Update%sRequest) error {
                	if req.ID <= 0 {
                		return errors.New("记录 ID 不能为空")
                	}
                	if err := s.repo.Update(req); err != nil {
                		slog.Error("update %s failed", "error", err, "id", req.ID)
                		return errors.New("更新%s失败")
                	}
                	return nil
                }

                func (s *Service) Delete(id int64) error {
                	if id <= 0 {
                		return errors.New("记录 ID 不能为空")
                	}
                	if err := s.repo.Delete(id); err != nil {
                		slog.Error("delete %s failed", "error", err, "id", id)
                		return errors.New("删除%s失败")
                	}
                	return nil
                }

                func (s *Service) Detail(id int64) (%sVO, error) {
                	item, err := s.repo.FindByID(id)
                	if err != nil {
                		return %sVO{}, errors.New("记录不存在")
                	}
                	return toVO(item), nil
                }

                func (s *Service) List(req QueryRequest) (%s, error) {
                %s
                	records, total, err := s.repo.List(req)
                	_ = total
                	if err != nil {
                		slog.Error("list %s failed", "error", err)
                		return %s, errors.New("查询%s失败")
                	}
                	vos := make([]%sVO, 0, len(records))
                	for _, item := range records {
                		vos = append(vos, toVO(item))
                	}
                	return %s, nil
                }
                %s%s

                func toVO(item %s) %sVO {
                	return %sVO(item)
                }
                """.formatted(
                recipe.packageName(),
                imports.stripTrailing(),
                recipe.structName(),
                recipe.packageName(),
                recipe.label(),
                recipe.structName(),
                recipe.packageName(),
                recipe.label(),
                recipe.packageName(),
                recipe.label(),
                recipe.structName(),
                recipe.structName(),
                listReturnType,
                normalizeLine,
                recipe.packageName(),
                listErrorReturn,
                recipe.label(),
                recipe.structName(),
                listSuccessReturn,
                batchDelete,
                importExport,
                recipe.structName(),
                recipe.structName(),
                recipe.structName()
        );
    }
}
