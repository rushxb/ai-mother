package com.yupi.yuaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.yupi.yuaicodemother.core.handler.GenerationStreamEvent;
import com.yupi.yuaicodemother.mapper.GenerationBuildLogMapper;
import com.yupi.yuaicodemother.mapper.GenerationModelCallMapper;
import com.yupi.yuaicodemother.mapper.GenerationTaskMapper;
import com.yupi.yuaicodemother.model.entity.GenerationBuildLog;
import com.yupi.yuaicodemother.model.entity.GenerationModelCall;
import com.yupi.yuaicodemother.model.entity.GenerationTask;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.service.GenerationTraceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GenerationTraceServiceImpl implements GenerationTraceService {

    private static final int MAX_STAGE_LENGTH = 64;
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_MEMORY_SUMMARY_LENGTH = 6000;

    @Resource
    private GenerationTaskMapper generationTaskMapper;

    @Resource
    private GenerationBuildLogMapper generationBuildLogMapper;

    @Resource
    private GenerationModelCallMapper generationModelCallMapper;

    @Override
    public void startTask(String taskId,
                          Long appId,
                          Long userId,
                          CodeGenTypeEnum originalType,
                          CodeGenTypeEnum targetType,
                          String userPrompt,
                          String enhancedPrompt,
                          boolean requiresBuildValidation,
                          String qualityGate,
                          String orchestrationMode) {
        if (StrUtil.isBlank(taskId)) {
            return;
        }
        try {
            GenerationTask existingTask = getByTaskId(taskId);
            if (existingTask != null) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            GenerationTask task = GenerationTask.builder()
                    .taskId(taskId)
                    .appId(appId)
                    .userId(userId)
                    .originalCodeGenType(enumValue(originalType))
                    .targetCodeGenType(enumValue(targetType))
                    .status("running")
                    .stage("start")
                    .userPrompt(userPrompt)
                    .enhancedPrompt(enhancedPrompt)
                    .requiresBuildValidation(requiresBuildValidation ? 1 : 0)
                    .qualityGate(qualityGate)
                    .orchestrationMode(orchestrationMode)
                    .startTime(now)
                    .totalTokens(0L)
                    .creditCost(0L)
                    .creditCharged(0)
                    .createTime(now)
                    .updateTime(now)
                    .isDelete(0)
                    .build();
            generationTaskMapper.insert(task);
            log.info("生成任务 trace 已创建，taskId: {}, appId: {}, targetType: {}", taskId, appId, enumValue(targetType));
        } catch (Exception e) {
            log.error("记录生成任务开始失败，taskId: {}, appId: {}", taskId, appId, e);
        }
    }

    @Override
    public void updateStage(String taskId, String stage, String message) {
        if (StrUtil.isBlank(taskId)) {
            return;
        }
        try {
            GenerationTask task = getByTaskId(taskId);
            if (task == null) {
                return;
            }
            task.setStage(limit(stage, MAX_STAGE_LENGTH));
            task.setUpdateTime(LocalDateTime.now());
            if (StrUtil.isNotBlank(message)) {
                task.setErrorMessage(limit(message, MAX_MESSAGE_LENGTH));
            }
            generationTaskMapper.update(task);
        } catch (Exception e) {
            log.warn("记录生成阶段失败，taskId: {}", taskId, e);
        }
    }

    @Override
    public void updateMemorySummary(String taskId, String memorySummary) {
        if (StrUtil.isBlank(taskId)) {
            return;
        }
        try {
            GenerationTask task = getByTaskId(taskId);
            if (task == null) {
                return;
            }
            task.setMemorySummary(limit(memorySummary, MAX_MEMORY_SUMMARY_LENGTH));
            task.setUpdateTime(LocalDateTime.now());
            generationTaskMapper.update(task);
        } catch (Exception e) {
            log.warn("记录生成记忆摘要失败，taskId: {}", taskId, e);
        }
    }

    @Override
    public void completeTask(String taskId, String status, Instant startedAt, String errorMessage) {
        if (StrUtil.isBlank(taskId)) {
            return;
        }
        try {
            GenerationTask task = getByTaskId(taskId);
            if (task == null) {
                return;
            }
            task.setStatus(StrUtil.blankToDefault(status, "unknown"));
            task.setStage("completed");
            task.setEndTime(LocalDateTime.now());
            task.setUpdateTime(LocalDateTime.now());
            if (startedAt != null) {
                task.setDurationMs(Duration.between(startedAt, Instant.now()).toMillis());
            }
            task.setErrorMessage(limit(errorMessage, MAX_MESSAGE_LENGTH));
            generationTaskMapper.update(task);
        } catch (Exception e) {
            log.warn("记录生成任务结束失败，taskId: {}", taskId, e);
        }
    }

    @Override
    public void recordEvent(String taskId, Long appId, Long userId, GenerationStreamEvent event) {
        if (event == null || StrUtil.isBlank(taskId)) {
            return;
        }
        if (GenerationStreamEvent.BUILD_RESULT.equals(event.getType())) {
            recordBuildResult(taskId, appId, userId, event);
        }
    }

    @Override
    public void recordBuildResult(String taskId, Long appId, Long userId, GenerationStreamEvent event) {
        if (event == null || event.getData() == null) {
            return;
        }
        try {
            Map<String, Object> data = event.getData();
            GenerationBuildLog buildLog = GenerationBuildLog.builder()
                    .taskId(taskId)
                    .appId(appId)
                    .userId(userId)
                    .projectPath(stringValue(data.get("projectPath")))
                    .stage(stringValue(data.get("stage")))
                    .success(Boolean.TRUE.equals(data.get("success")) ? 1 : 0)
                    .summary(stringValue(data.get("summary")))
                    .report(StrUtil.blankToDefault(stringValue(data.get("report")), event.getText()))
                    .qualityGate(stringValue(data.get("qualityGate")))
                    .willAutoRepair(Boolean.TRUE.equals(data.get("willAutoRepair")) ? 1 : 0)
                    .createTime(LocalDateTime.now())
                    .isDelete(0)
                    .build();
            generationBuildLogMapper.insert(buildLog);
        } catch (Exception e) {
            log.warn("记录构建结果失败，taskId: {}", taskId, e);
        }
    }

    @Override
    public void recordModelCall(String taskId, Long appId, Long userId, Map<String, Object> metadata) {
        if (StrUtil.isBlank(taskId) || metadata == null || metadata.isEmpty()) {
            return;
        }
        try {
            GenerationModelCall modelCall = GenerationModelCall.builder()
                    .taskId(taskId)
                    .appId(appId)
                    .userId(userId)
                    .provider(stringValue(metadata.get("provider")))
                    .model(stringValue(metadata.get("model")))
                    .promptTokens(intValue(metadata.get("promptTokens")))
                    .completionTokens(intValue(metadata.get("completionTokens")))
                    .totalTokens(intValue(metadata.get("totalTokens")))
                    .latencyMs(longValue(metadata.get("latencyMs")))
                    .finishReason(stringValue(metadata.get("finishReason")))
                    .usageSource(StrUtil.blankToDefault(stringValue(metadata.get("usageSource")), "OFFICIAL"))
                    .rawMetadataJson(toJson(metadata))
                    .createTime(LocalDateTime.now())
                    .isDelete(0)
                    .build();
            generationModelCallMapper.insert(modelCall);
        } catch (Exception e) {
            log.warn("记录模型调用失败，taskId: {}", taskId, e);
        }
    }

    @Override
    public GenerationTask getByTaskId(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            return null;
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq(GenerationTask::getTaskId, taskId)
                .limit(1);
        return generationTaskMapper.selectOneByQuery(queryWrapper);
    }

    @Override
    public List<GenerationTask> listRecentTasksByAppId(Long appId, int limit) {
        if (appId == null) {
            return List.of();
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq(GenerationTask::getAppId, appId)
                .orderBy(GenerationTask::getCreateTime, false)
                .limit(normalizeLimit(limit));
        return generationTaskMapper.selectListByQuery(queryWrapper);
    }

    @Override
    public List<GenerationBuildLog> listRecentBuildLogsByAppId(Long appId, int limit) {
        if (appId == null) {
            return List.of();
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq(GenerationBuildLog::getAppId, appId)
                .orderBy(GenerationBuildLog::getCreateTime, false)
                .limit(normalizeLimit(limit));
        return generationBuildLogMapper.selectListByQuery(queryWrapper);
    }

    @Override
    public List<GenerationBuildLog> listBuildLogsByTaskId(String taskId, int limit) {
        if (StrUtil.isBlank(taskId)) {
            return List.of();
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq(GenerationBuildLog::getTaskId, taskId)
                .orderBy(GenerationBuildLog::getCreateTime, false)
                .limit(normalizeLimit(limit));
        return generationBuildLogMapper.selectListByQuery(queryWrapper);
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 1;
        }
        return Math.min(limit, 20);
    }

    private String enumValue(CodeGenTypeEnum codeGenTypeEnum) {
        return codeGenTypeEnum == null ? null : codeGenTypeEnum.getValue();
    }

    private String toJson(Object value) {
        return value == null ? null : JSONUtil.toJsonStr(value);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
