package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Owns atomic application generation-state transitions.
 *
 * <p>Every mutable state is scoped to a task ID. Stage and snapshot updates from an older task
 * therefore cannot overwrite a newer task, while a bounded lease allows recovery after process loss.</p>
 */
@Service
public class GenerationAppStateService {

    private static final int MAX_TASK_ID_LENGTH = 128;
    private static final int MAX_STAGE_LENGTH = 64;
    private static final int MAX_MESSAGE_LENGTH = 1_000_000;
    private static final Duration LEASE_SAFETY_MARGIN = Duration.ofMinutes(1);

    private final AppMapper appMapper;
    private final GenerationRuntimeProperties runtimeProperties;
    private final Clock clock;

    @Autowired
    public GenerationAppStateService(AppMapper appMapper,
                                     GenerationRuntimeProperties runtimeProperties) {
        this(appMapper, runtimeProperties, Clock.systemDefaultZone());
    }

    GenerationAppStateService(AppMapper appMapper,
                              GenerationRuntimeProperties runtimeProperties,
                              Clock clock) {
        this.appMapper = Objects.requireNonNull(appMapper, "appMapper");
        this.runtimeProperties = Objects.requireNonNull(runtimeProperties, "runtimeProperties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void claimGenerationState(Long appId,
                                     String taskId,
                                     String generatingStage,
                                     CodeGenTypeEnum targetType) {
        long normalizedAppId = requireAppId(appId);
        String normalizedTaskId = requireTaskId(taskId);
        String normalizedStage = requireStage(generatingStage);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime leaseUntil = now.plus(leaseDuration());
        int updatedRows = appMapper.claimGenerationState(
                normalizedAppId,
                normalizedTaskId,
                normalizedStage,
                targetType == null ? null : targetType.getValue(),
                now,
                leaseUntil
        );
        if (updatedRows == 1) {
            return;
        }
        App currentState = appMapper.selectGenerationState(normalizedAppId);
        if (currentState == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在，无法开始生成");
        }
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "当前应用正在执行其他生成任务，请稍后再试");
    }

    public void updateOwnedGenerationStage(Long appId,
                                           String taskId,
                                           String generatingStage,
                                           String generatingMessage) {
        long normalizedAppId = requireAppId(appId);
        String normalizedTaskId = requireTaskId(taskId);
        String normalizedStage = requireStage(generatingStage);
        String normalizedMessage = normalizeMessage(generatingMessage);
        int updatedRows = appMapper.updateOwnedGenerationStage(
                normalizedAppId,
                normalizedTaskId,
                normalizedStage,
                normalizedMessage,
                nextLeaseUntil()
        );
        requireOwnedWrite(updatedRows, normalizedAppId);
    }

    public void updateOwnedGenerationSnapshot(Long appId,
                                              String taskId,
                                              String generatingMessage) {
        long normalizedAppId = requireAppId(appId);
        String normalizedTaskId = requireTaskId(taskId);
        String normalizedMessage = normalizeMessage(generatingMessage);
        int updatedRows = appMapper.updateOwnedGenerationSnapshot(
                normalizedAppId,
                normalizedTaskId,
                normalizedMessage,
                nextLeaseUntil()
        );
        requireOwnedWrite(updatedRows, normalizedAppId);
    }

    public void updateOwnedCodeGenType(Long appId, String taskId, CodeGenTypeEnum codeGenType) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        }
        long normalizedAppId = requireAppId(appId);
        String normalizedTaskId = requireTaskId(taskId);
        int updatedRows = appMapper.updateOwnedCodeGenType(
                normalizedAppId, normalizedTaskId, codeGenType.getValue(), nextLeaseUntil());
        requireOwnedWrite(updatedRows, normalizedAppId);
    }

    public boolean releaseOwnedGenerationState(Long appId, String taskId) {
        long normalizedAppId = requireAppId(appId);
        String normalizedTaskId = requireTaskId(taskId);
        return appMapper.releaseOwnedGenerationState(normalizedAppId, normalizedTaskId) == 1;
    }

    private void requireOwnedWrite(int updatedRows, long appId) {
        if (updatedRows == 1) {
            return;
        }
        if (appMapper.selectGenerationState(appId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在，无法更新生成状态");
        }
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成状态所有权已失效");
    }

    private LocalDateTime nextLeaseUntil() {
        return LocalDateTime.now(clock).plus(leaseDuration());
    }

    private Duration leaseDuration() {
        Duration taskTimeout = runtimeProperties.getTaskTimeout();
        if (taskTimeout == null || taskTimeout.isZero() || taskTimeout.isNegative()) {
            throw new IllegalStateException("generation task timeout must be positive");
        }
        return taskTimeout.plus(LEASE_SAFETY_MARGIN);
    }

    private long requireAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        }
        return appId;
    }

    private String requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成任务 ID 不能为空");
        }
        String normalized = taskId.trim();
        if (normalized.length() > MAX_TASK_ID_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成任务 ID 长度不合法");
        }
        return normalized;
    }

    private String requireStage(String stage) {
        if (stage == null || stage.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成阶段不能为空");
        }
        String normalized = stage.trim();
        if (normalized.length() > MAX_STAGE_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成阶段长度不合法");
        }
        return normalized;
    }

    private String normalizeMessage(String message) {
        if (message == null) {
            return "";
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成状态消息过长");
        }
        return message;
    }
}
