package com.rush.rushaicodemother.service.aimodel;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.event.AiModelConfigChangedEvent;
import com.rush.rushaicodemother.model.vo.AiModelAdminVO;
import com.rush.rushaicodemother.model.vo.AiModelConnectionTestResultVO;
import com.rush.rushaicodemother.model.vo.AiModelPublicVO;
import com.rush.rushaicodemother.model.vo.SupportedAiModelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** AI 模型管理用例的默认实现。 */
@Service
@RequiredArgsConstructor
public class DefaultAiModelManagementService implements AiModelManagementService {

    private final AiModelPersistenceService persistenceService;
    private final AiModelConfigurationAssembler configurationAssembler;
    private final AiModelConfigurationPolicy configurationPolicy;
    private final AiModelViewAssembler viewAssembler;
    private final AiModelConnectionTester connectionTester;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long createModel(CreateCommand command, long operatorUserId) {
        if (operatorUserId <= 0) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "缺少有效的管理员身份");
        }
        AiModelConfiguration configuration = configurationPolicy.normalizeAndValidate(
                configurationAssembler.fromCreateCommand(command, operatorUserId)
        );
        if (persistenceService.existsActiveIdentity(configuration.getProvider(), configuration.getModelId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该模型已存在");
        }
        if (configuration.enabled()) {
            persistenceService.disableOtherEnabledModels(configuration.getModelType(), null);
        }
        long modelId = persistenceService.insert(configuration);
        publishConfigurationChanged();
        return modelId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateModel(UpdateCommand command) {
        if (command == null || command.id() == null || command.id() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型更新参数不合法");
        }
        AiModelConfiguration existing = requireLockedModel(command.id());
        AiModelConfiguration updated = configurationPolicy.normalizeAndValidate(
                configurationAssembler.applyUpdate(existing, command)
        );
        if (updated.enabled()) {
            persistenceService.disableOtherEnabledModels(updated.getModelType(), updated.getId());
        }
        persistenceService.update(updated);
        publishConfigurationChanged();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteModel(long modelId) {
        requireLockedModel(modelId);
        persistenceService.logicallyDelete(modelId);
        publishConfigurationChanged();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiModelAdminVO toggleModelEnabled(long modelId) {
        AiModelConfiguration existing = requireLockedModel(modelId);
        boolean enable = !existing.enabled();
        AiModelConfiguration updated = existing.toBuilder()
                .isEnabled(enable ? 1 : 0)
                .build();
        if (enable) {
            updated = configurationPolicy.normalizeAndValidate(updated);
            persistenceService.disableOtherEnabledModels(updated.getModelType(), updated.getId());
        }
        persistenceService.update(updated);
        publishConfigurationChanged();
        return viewAssembler.toAdminView(updated);
    }

    @Override
    public AiModelAdminVO getModelById(long modelId) {
        if (modelId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型 ID 不合法");
        }
        AiModelConfiguration configuration = persistenceService.findActiveById(modelId);
        if (configuration == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "模型不存在或已删除");
        }
        return viewAssembler.toAdminView(configuration);
    }

    @Override
    public Page<AiModelAdminVO> pageModels(Query query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型查询参数不能为空");
        }
        Page<AiModelConfiguration> configurationPage = persistenceService.pageActive(
                new AiModelPersistenceService.QueryCriteria(
                        query.pageNumber(), query.pageSize(), query.provider(), query.modelType(),
                        query.isEnabled(), query.keyword(), query.sortField(), query.sortOrder()
                )
        );
        Page<AiModelAdminVO> result = new Page<>(
                configurationPage.getPageNumber(),
                configurationPage.getPageSize(),
                configurationPage.getTotalRow()
        );
        result.setRecords(configurationPage.getRecords().stream()
                .map(viewAssembler::toAdminView)
                .toList());
        return result;
    }

    @Override
    public List<AiModelPublicVO> listEnabledModels() {
        return listEnabledModelsByType(null);
    }

    @Override
    public List<AiModelPublicVO> listEnabledModelsByType(String modelType) {
        return persistenceService.findEnabled(modelType).stream()
                .map(viewAssembler::toPublicView)
                .toList();
    }

    @Override
    public List<SupportedAiModelVO> listSupportedModels() {
        return configurationPolicy.listSupportedModels();
    }

    @Override
    public AiModelConnectionTestResultVO testSavedModelConnection(long modelId) {
        AiModelConfiguration configuration = persistenceService.findActiveById(modelId);
        if (configuration == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "模型不存在或已删除");
        }
        return connectionTester.test(configurationPolicy.toRuntimeConfiguration(configuration));
    }

    @Override
    public AiModelConnectionTestResultVO testConfiguration(CreateCommand command) {
        AiModelConfiguration configuration = configurationPolicy.normalizeAndValidate(
                configurationAssembler.fromCreateCommand(command, null)
        );
        return connectionTester.test(configurationPolicy.toRuntimeConfiguration(configuration));
    }

    private AiModelConfiguration requireLockedModel(long modelId) {
        if (modelId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型 ID 不合法");
        }
        AiModelConfiguration configuration = persistenceService.lockActiveById(modelId);
        if (configuration == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "模型不存在或已删除");
        }
        return configuration;
    }

    private void publishConfigurationChanged() {
        eventPublisher.publishEvent(new AiModelConfigChangedEvent());
    }
}
