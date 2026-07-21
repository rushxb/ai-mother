package com.rush.rushaicodemother.service.aimodel;

import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.common.query.SortFieldWhitelist;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.AiModelMapper;
import com.rush.rushaicodemother.model.entity.AiModel;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** MyBatis AI 模型持久化适配器。 */
@Repository
@RequiredArgsConstructor
public class DefaultAiModelPersistenceService implements AiModelPersistenceService {

    private static final SortFieldWhitelist SORT_FIELDS = SortFieldWhitelist.of("sortOrder", Map.of(
            "modelName", "modelName",
            "provider", "provider",
            "modelId", "modelId",
            "modelType", "modelType",
            "isEnabled", "isEnabled",
            "sortOrder", "sortOrder",
            "createTime", "createTime",
            "updateTime", "updateTime"
    ));

    private final AiModelMapper mapper;

    @Override
    public AiModelConfiguration findActiveById(long modelId) {
        requirePositiveId(modelId);
        return toConfiguration(mapper.selectActiveById(modelId));
    }

    @Override
    public AiModelConfiguration lockActiveById(long modelId) {
        requirePositiveId(modelId);
        return toConfiguration(mapper.selectActiveByIdForUpdate(modelId));
    }

    @Override
    public List<AiModelConfiguration> findEnabled(String modelType) {
        return mapper.selectEnabled(trimToNull(modelType)).stream()
                .map(this::toConfiguration)
                .toList();
    }

    @Override
    public Page<AiModelConfiguration> pageActive(QueryCriteria criteria) {
        if (criteria == null || criteria.pageNumber() <= 0 || criteria.pageSize() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型分页参数不合法");
        }
        String provider = trimToNull(criteria.provider());
        String modelType = trimToNull(criteria.modelType());
        String keyword = trimToNull(criteria.keyword());
        String sortColumn = SORT_FIELDS.resolve(criteria.sortField());
        String sortDirection = "ascend".equals(criteria.sortOrder()) ? "ASC" : "DESC";
        long total = mapper.countActive(provider, modelType, criteria.isEnabled(), keyword);
        Page<AiModelConfiguration> page = new Page<>(criteria.pageNumber(), criteria.pageSize(), total);
        if (total == 0) {
            page.setRecords(List.of());
            return page;
        }
        long offset = (long) (criteria.pageNumber() - 1) * criteria.pageSize();
        page.setRecords(mapper.selectActivePage(
                        provider, modelType, criteria.isEnabled(), keyword,
                        sortColumn, sortDirection, criteria.pageSize(), offset
                ).stream()
                .map(this::toConfiguration)
                .toList());
        return page;
    }

    @Override
    public boolean existsActiveIdentity(String provider, String modelId) {
        return mapper.selectActiveIdentityId(provider, modelId) != null;
    }

    @Override
    public long insert(AiModelConfiguration configuration) {
        AiModel entity = toEntity(configuration);
        try {
            requireOneAffectedRow(mapper.insertModel(entity), "创建 AI 模型配置");
        } catch (DuplicateKeyException exception) {
            throw configurationConflict(exception);
        }
        if (entity.getId() == null || entity.getId() <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "创建 AI 模型配置后未返回主键");
        }
        return entity.getId();
    }

    @Override
    public void update(AiModelConfiguration configuration) {
        if (configuration == null || configuration.getId() == null || configuration.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型更新配置不合法");
        }
        try {
            requireOneAffectedRow(mapper.updateActiveModel(toEntity(configuration)), "更新 AI 模型配置");
        } catch (DuplicateKeyException exception) {
            throw configurationConflict(exception);
        }
    }


    @Override
    public void logicallyDelete(long modelId) {
        requirePositiveId(modelId);
        requireOneAffectedRow(mapper.logicallyDeleteActiveModel(modelId), "删除 AI 模型配置");
    }

    private AiModelConfiguration toConfiguration(AiModel entity) {
        if (entity == null) {
            return null;
        }
        return AiModelConfiguration.builder()
                .id(entity.getId())
                .modelName(entity.getModelName())
                .provider(entity.getProvider())
                .modelId(entity.getModelId())
                .description(entity.getDescription())
                .baseUrl(entity.getBaseUrl())
                .secretRef(entity.getSecretRef())
                .secretFingerprint(entity.getSecretFingerprint())
                .secretKeyId(entity.getSecretKeyId())
                .maxTokens(entity.getMaxTokens())
                .temperature(entity.getTemperature())
                .isEnabled(entity.getIsEnabled())
                .modelType(entity.getModelType())
                .supportsThinking(entity.getSupportsThinking())
                .sortOrder(entity.getSortOrder())
                .configJson(entity.getConfigJson())
                .userId(entity.getUserId())
                .editTime(entity.getEditTime())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }

    private AiModel toEntity(AiModelConfiguration configuration) {
        if (configuration == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型持久化配置不能为空");
        }
        return AiModel.builder()
                .id(configuration.getId())
                .modelName(configuration.getModelName())
                .provider(configuration.getProvider())
                .modelId(configuration.getModelId())
                .description(configuration.getDescription())
                .baseUrl(configuration.getBaseUrl())
                .secretRef(configuration.getSecretRef())
                .secretFingerprint(configuration.getSecretFingerprint())
                .secretKeyId(configuration.getSecretKeyId())
                .maxTokens(configuration.getMaxTokens())
                .temperature(configuration.getTemperature())
                .isEnabled(configuration.getIsEnabled())
                .modelType(configuration.getModelType())
                .supportsThinking(configuration.getSupportsThinking())
                .sortOrder(configuration.getSortOrder())
                .configJson(configuration.getConfigJson())
                .userId(configuration.getUserId())
                .editTime(configuration.getEditTime())
                .createTime(configuration.getCreateTime())
                .updateTime(configuration.getUpdateTime())
                .isDelete(0)
                .build();
    }

    private String trimToNull(String value) {
        String trimmed = StrUtil.trim(value);
        return StrUtil.isBlank(trimmed) ? null : trimmed;
    }

    private void requirePositiveId(long modelId) {
        if (modelId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型 ID 不合法");
        }
    }

    private void requireOneAffectedRow(int affectedRows, String operation) {
        if (affectedRows != 1) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, operation + "失败：影响行数不符合预期");
        }
    }

    private BusinessException configurationConflict(DuplicateKeyException exception) {
        return new BusinessException(
                ErrorCode.PARAMS_ERROR,
                "模型已存在或同类型模型启用状态发生并发冲突，请刷新后重试",
                exception
        );
    }
}
