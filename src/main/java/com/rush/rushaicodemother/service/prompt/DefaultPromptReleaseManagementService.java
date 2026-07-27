package com.rush.rushaicodemother.service.prompt;

import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.ai.prompt.PromptCatalogSnapshot;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseAction;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseCapabilities;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseConflictException;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseHistoryEntry;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseMutation;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRecord;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRepository;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRuntime;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseState;
import com.rush.rushaicodemother.config.AiPromptCatalogProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.vo.PromptCatalogAdminVO;
import com.rush.rushaicodemother.model.vo.PromptReleaseAdminVO;
import com.rush.rushaicodemother.model.vo.PromptReleaseHistoryVO;
import com.rush.rushaicodemother.model.vo.PromptReleaseMutationVO;
import com.rush.rushaicodemother.model.vo.PromptVersionAdminVO;
import com.rush.rushaicodemother.monitor.PromptReleaseMetricsCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 提示词发布管理服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultPromptReleaseManagementService implements PromptReleaseManagementService {

    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    private static final int MAX_HISTORY_LIMIT = 100;
    private static final int MAX_CHANGE_NOTE_LENGTH = 512;

    private final AiPromptCatalogProperties properties;
    private final PromptCatalog promptCatalog;
    private final PromptReleaseRuntime runtime;
    private final PromptReleaseRepository repository;
    private final PromptReleaseRefreshService refreshService;
    private final PromptReleaseMetricsCollector metricsCollector;
    private final PromptReleaseTransactionCoordinator transactionCoordinator;

    @Override
    public PromptCatalogAdminVO getOverview() {
        PromptReleaseState durable = repository.loadCurrent();
        PromptCatalogSnapshot active = promptCatalog.snapshot();
        PromptReleaseCapabilities capabilities = runtime.capabilities();
        TreeSet<String> keys = new TreeSet<>(capabilities.contentHashesByPromptAndVersion().keySet());
        keys.addAll(active.releases().keySet());
        keys.addAll(durable.releases().keySet());

        List<PromptReleaseAdminVO> releases = new ArrayList<>(keys.size());
        for (String promptKey : keys) {
            releases.add(toAdminView(
                    promptKey,
                    active.releases().get(promptKey),
                    durable.releases().get(promptKey),
                    capabilities.contentHashesByPromptAndVersion().getOrDefault(promptKey, Map.of())
            ));
        }
        return new PromptCatalogAdminVO(
                active.bundleId(),
                durable.revision(),
                runtime.activeRevision(),
                releases
        );
    }

    @Override
    public PromptReleaseMutationVO publish(PublishCommand command, long operatorUserId) {
        requireRuntimeReleaseControl();
        requireOperator(operatorUserId);
        if (command == null || command.expectedRevision() < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Prompt 发布参数不合法");
        }
        String promptKey = normalizeKey(command.promptKey());
        PromptReleaseSpec release = validateRelease(
                promptKey,
                new PromptReleaseSpec(
                        command.stableVersion(),
                        command.canaryVersion(),
                        command.canaryPercentage()
                )
        );
        PromptReleaseMutation mutation = new PromptReleaseMutation(
                promptKey,
                release,
                command.expectedRevision(),
                operatorUserId,
                normalizeChangeNote(command.changeNote()),
                PromptReleaseAction.PUBLISH,
                null,
                command.evidenceId()
        );
        return mutate(mutation);
    }

    @Override
    public PromptReleaseMutationVO rollback(RollbackCommand command, long operatorUserId) {
        requireRuntimeReleaseControl();
        requireOperator(operatorUserId);
        if (command == null || command.targetRevision() <= 0 || command.expectedRevision() < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Prompt 回滚参数不合法");
        }
        String promptKey = normalizeKey(command.promptKey());
        PromptReleaseHistoryEntry target = repository.findHistory(promptKey, command.targetRevision())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR, "目标 Prompt 发布历史不存在"));
        PromptReleaseSpec release = validateRelease(promptKey, target.release());
        PromptReleaseMutation mutation = new PromptReleaseMutation(
                promptKey,
                release,
                command.expectedRevision(),
                operatorUserId,
                normalizeChangeNote(command.changeNote()),
                PromptReleaseAction.ROLLBACK,
                target.revision(),
                ""
        );
        return mutate(mutation);
    }

    @Override
    public List<PromptReleaseHistoryVO> listHistory(String promptKey, int limit) {
        String normalizedKey = normalizeKey(promptKey);
        if (limit <= 0 || limit > MAX_HISTORY_LIMIT) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Prompt 历史查询数量不合法");
        }
        return repository.listHistory(normalizedKey, limit).stream()
                .map(this::toHistoryView)
                .toList();
    }

    private PromptReleaseMutationVO mutate(PromptReleaseMutation mutation) {
        PromptReleaseRecord persisted;
        try {
            persisted = transactionCoordinator.mutate(mutation);
        } catch (PromptReleaseConflictException exception) {
            metricsCollector.recordMutation(mutation.action().name(), "conflict");
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Prompt 发布版本已变化，请刷新后重试"
            );
        } catch (RuntimeException exception) {
            metricsCollector.recordMutation(mutation.action().name(), "failed");
            throw exception;
        }

        try {
            refreshService.refreshNow();
        } catch (RuntimeException exception) {
            log.error("Prompt release was committed but local activation is pending, revision={}",
                    persisted.revision(), LogExceptionSanitizer.sanitize(exception));
        }
        metricsCollector.recordMutation(mutation.action().name(), "success");
        long activeRevision = runtime.activeRevision();
        return new PromptReleaseMutationVO(
                persisted.promptKey(),
                persisted.revision(),
                activeRevision,
                promptCatalog.bundleId(),
                activeRevision >= persisted.revision()
        );
    }

    private PromptReleaseSpec validateRelease(String promptKey, PromptReleaseSpec release) {
        PromptReleaseCapabilities capabilities = runtime.capabilities();
        if (!VERSION_PATTERN.matcher(release.stableVersion()).matches()
                || !capabilities.supports(promptKey, release.stableVersion())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Prompt 稳定版本不存在于当前制品");
        }
        if (release.canaryPercentage() < 0 || release.canaryPercentage() > 100) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Prompt 灰度比例必须在 0 到 100 之间");
        }
        if (!release.hasCanary()) {
            if (!release.canaryVersion().isBlank()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "灰度比例为 0 时不能指定灰度版本");
            }
            return new PromptReleaseSpec(release.stableVersion(), "", 0);
        }
        if (!VERSION_PATTERN.matcher(release.canaryVersion()).matches()
                || !capabilities.supports(promptKey, release.canaryVersion())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Prompt 灰度版本不存在于当前制品");
        }
        if (release.stableVersion().equals(release.canaryVersion())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Prompt 稳定版本与灰度版本不能相同");
        }
        return release;
    }

    private PromptReleaseAdminVO toAdminView(
            String promptKey,
            PromptCatalogSnapshot.PromptRelease active,
            PromptReleaseRecord durable,
            Map<String, String> availableVersions
    ) {
        List<PromptVersionAdminVO> versions = availableVersions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new PromptVersionAdminVO(entry.getKey(), entry.getValue()))
                .toList();
        return new PromptReleaseAdminVO(
                promptKey,
                active == null ? "" : active.stableVersion(),
                active == null ? "" : active.stableContentHash(),
                active == null ? "" : active.canaryVersion(),
                active == null ? "" : active.canaryContentHash(),
                active == null ? 0 : active.canaryPercentage(),
                durable == null ? 0L : durable.revision(),
                durable == null ? null : durable.updatedBy(),
                durable == null ? "" : durable.changeNote(),
                durable == null ? null : durable.updatedAt(),
                versions
        );
    }

    private PromptReleaseHistoryVO toHistoryView(PromptReleaseHistoryEntry entry) {
        return new PromptReleaseHistoryVO(
                entry.promptKey(),
                entry.release().stableVersion(),
                entry.release().canaryVersion(),
                entry.release().canaryPercentage(),
                entry.revision(),
                entry.action().name(),
                entry.sourceRevision(),
                entry.updatedBy(),
                entry.changeNote(),
                entry.evidenceId(),
                entry.createdAt()
        );
    }

    private String normalizeKey(String promptKey) {
        String normalized = promptKey == null ? "" : promptKey.trim();
        if (!KEY_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Prompt Key 不合法");
        }
        return normalized;
    }

    private String normalizeChangeNote(String changeNote) {
        String normalized = changeNote == null ? "" : changeNote.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank() || normalized.length() > MAX_CHANGE_NOTE_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Prompt 发布说明不能为空且不能超过 512 字符");
        }
        return normalized;
    }

    private void requireRuntimeReleaseControl() {
        if (!properties.getRuntimeReleases().isEnabled()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "Prompt 运行时发布控制未启用");
        }
    }

    private void requireOperator(long operatorUserId) {
        if (operatorUserId <= 0) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "缺少有效的管理员身份");
        }
    }
}
