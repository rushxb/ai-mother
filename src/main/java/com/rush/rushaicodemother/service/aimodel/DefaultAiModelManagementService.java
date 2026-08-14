package com.rush.rushaicodemother.service.aimodel;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.event.AiModelConfigChangedEvent;
import com.rush.rushaicodemother.model.vo.AiModelAdminVO;
import com.rush.rushaicodemother.model.vo.AiModelConnectionTestResultVO;
import com.rush.rushaicodemother.model.vo.AiModelPublicVO;
import com.rush.rushaicodemother.model.vo.SupportedAiModelVO;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceRecord;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationReleaseEvidenceVerifier;
import com.rush.rushaicodemother.service.release.AiReleaseAuditService;
import com.rush.rushaicodemother.service.release.AiReleaseCoordinationLock;
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
    private final GenerationReleaseEvidenceVerifier evidenceVerifier;
    private final AiReleaseAuditService releaseAuditService;
    private final AiReleaseCoordinationLock coordinationLock;

    /**
 * 创建模型。
 *
 * @param command 命令
 * @param operatorUserId 目标资源编号
 * @return 计算或处理后的数值结果
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long createModel(CreateCommand command, long operatorUserId) {
        if (operatorUserId <= 0) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "缺少有效的管理员身份");
        }
        AiModelConfiguration candidate = configurationAssembler.fromCreateCommand(command, operatorUserId);
        if (candidate.enabled()) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "新模型必须先以停用状态保存，完成 Benchmark 后再通过证据门禁启用"
            );
        }
        AiModelConfiguration configuration = configurationPolicy.normalizeAndValidate(candidate);
        if (persistenceService.existsActiveIdentity(configuration.getProvider(), configuration.getModelId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该模型已存在");
        }
        long modelId = persistenceService.insert(configuration);
        publishConfigurationChanged();
        return modelId;
    }

    /**
 * 更新模型。
 *
 * @param command 命令
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateModel(UpdateCommand command) {
        if (command == null || command.id() == null || command.id() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型更新参数不合法");
        }
        AiModelConfiguration existing = requireLockedModel(command.id());
        if (existing.enabled()) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "在线模型必须先停用，才能修改配置并重新完成 Benchmark"
            );
        }
        AiModelConfiguration candidate = configurationAssembler.applyUpdate(existing, command);
        if (candidate.enabled()) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "模型只能通过带 Benchmark 证据的启用操作上线"
            );
        }
        AiModelConfiguration updated = configurationPolicy.normalizeAndValidate(candidate);
        persistenceService.update(updated);
        publishConfigurationChanged();
    }

    /**
 * 删除模型。
 *
 * @param modelId 模型编号
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteModel(long modelId) {
        coordinationLock.acquire();
        requireLockedModel(modelId);
        persistenceService.logicallyDelete(modelId);
        publishConfigurationChanged();
    }

    /**
 * 将当前对象转换为{@code ggle}模型启用。
 *
 * @param modelId 模型编号
 * @param evidenceId 证据编号
 * @param operatorUserId 目标资源编号
 * @return {@code ggle}模型启用
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiModelAdminVO toggleModelEnabled(long modelId,
                                             String evidenceId,
                                             long operatorUserId) {
        if (operatorUserId <= 0) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "缺少有效的管理员身份");
        }
        coordinationLock.acquire();
        AiModelConfiguration existing = requireLockedModel(modelId);
        boolean enable = !existing.enabled();
        AiModelConfiguration updated = existing.toBuilder()
                .isEnabled(enable ? 1 : 0)
                .build();
        if (enable) {
            updated = configurationPolicy.normalizeAndValidate(updated);
            GenerationBenchmarkEvidenceRecord evidence = evidenceVerifier.requirePassed(
                    evidenceId,
                    new GenerationBenchmarkEvidenceCandidate.AiModelEnable(modelId)
            );
            persistenceService.update(updated);
            releaseAuditService.recordModelEnable(evidence, operatorUserId, modelId);
        } else {
            persistenceService.update(updated);
        }
        publishConfigurationChanged();
        return viewAssembler.toAdminView(updated);
    }

    /**
 * 获取并返回模型按编号。
 *
 * @param modelId 模型编号
 * @return 默认 AI 模型管理
 */
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

    /**
 * 返回{@code page}模型。
 *
 * @param query 查询
 * @return 默认 AI 模型管理
 */
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

    /**
 * 列出符合条件的启用模型按类型。
 *
 * @param modelType 模型类型
 * @return 启用模型按类型集合
 */
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

    /**
 * 返回{@code test}{@code Saved}模型连接。
 *
 * @param modelId 模型编号
 * @return 默认 AI 模型管理
 */
    @Override
    public AiModelConnectionTestResultVO testSavedModelConnection(long modelId,
                                                                  long operatorUserId) {
        AiModelConfiguration configuration = persistenceService.findActiveById(modelId);
        if (configuration == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "模型不存在或已删除");
        }
        return connectionTester.test(
                configurationPolicy.toRuntimeConfiguration(configuration), operatorUserId);
    }

    /**
 * 返回{@code test}配置。
 *
 * @param command 命令
 * @return 默认 AI 模型管理
 */
    @Override
    public AiModelConnectionTestResultVO testConfiguration(CreateCommand command,
                                                            long operatorUserId) {
        AiModelConfiguration configuration = configurationPolicy.normalizeAndValidate(
                configurationAssembler.fromCreateCommand(command, null)
        );
        return connectionTester.test(
                configurationPolicy.toRuntimeConfiguration(configuration), operatorUserId);
    }

    /** 校验并返回有效的{@code Locked}模型。 */
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
