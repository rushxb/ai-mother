package com.rush.rushaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 生成前回滚点的强类型持久事实。
 *
 * <p>该 module 集中拥有制品 key、schema、状态与任务身份不变量；文件系统路径是否位于
 * 受控根目录，仍由快照和工作区 module 在真正读写文件前校验。</p>
 */
public record RollbackPoint(
        String schemaVersion,
        String provider,
        String status,
        Long appId,
        String taskId,
        String snapshotName,
        String snapshotPath,
        String projectPath,
        String sourceType,
        String targetType,
        int fileCount,
        String reason,
        LocalDateTime createdAt
) {

    public static final String KEY = "rollback_point";

    private static final String SCHEMA_VERSION = "v1";
    private static final String PROVIDER = "local_snapshot";
    private static final String STATUS_CREATED = "created";
    private static final String STATUS_SKIPPED = "skipped";
    private static final String ROLE = "Orchestrator";
    private static final String TITLE = "生成前回滚点";

    public RollbackPoint {
        schemaVersion = StrUtil.blankToDefault(schemaVersion, SCHEMA_VERSION);
        provider = StrUtil.blankToDefault(provider, PROVIDER);
        status = StrUtil.blankToDefault(status, STATUS_SKIPPED);
        reason = StrUtil.blankToDefault(reason, "");
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    /** 创建已完成快照复制的回滚点。 */
    public static RollbackPoint created(Long appId,
                                        String taskId,
                                        String snapshotName,
                                        String snapshotPath,
                                        String projectPath,
                                        String sourceType,
                                        String targetType,
                                        int fileCount) {
        return new RollbackPoint(
                SCHEMA_VERSION,
                PROVIDER,
                STATUS_CREATED,
                appId,
                taskId,
                snapshotName,
                snapshotPath,
                projectPath,
                sourceType,
                targetType,
                fileCount,
                "",
                LocalDateTime.now()
        );
    }

    /** 创建未执行快照复制但保留诊断原因的回滚点。 */
    public static RollbackPoint skipped(Long appId,
                                        String taskId,
                                        String projectPath,
                                        String sourceType,
                                        String targetType,
                                        String reason) {
        return new RollbackPoint(
                SCHEMA_VERSION,
                PROVIDER,
                STATUS_SKIPPED,
                appId,
                taskId,
                "",
                "",
                projectPath,
                sourceType,
                targetType,
                0,
                reason,
                LocalDateTime.now()
        );
    }

    /** 转换为稳定的持久化载荷。 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", schemaVersion);
        payload.put("provider", provider);
        payload.put("status", status);
        payload.put("appId", appId);
        payload.put("taskId", taskId);
        payload.put("snapshotName", snapshotName);
        payload.put("snapshotPath", snapshotPath);
        payload.put("projectPath", projectPath);
        payload.put("sourceType", sourceType);
        payload.put("targetType", targetType);
        payload.put("fileCount", fileCount);
        payload.put("reason", reason);
        payload.put("createdAt", createdAt.toString());
        return payload;
    }

    /**
     * 从持久制品恢复回滚点，并校验其是否属于当前应用和任务。
     *
     * <p>检查点恢复必须经过该 interface，防止其他任务的快照事实抑制当前任务创建回滚点。</p>
     */
    public static RollbackPoint fromArtifact(GenerationArtifact artifact,
                                             Long expectedAppId,
                                             String expectedTaskId) {
        if (artifact == null) {
            throw invalidField("artifact", "不能为空");
        }
        if (!KEY.equals(artifact.key())) {
            throw invalidField("key", "制品类型不匹配: " + artifact.key());
        }
        Map<String, Object> payload = artifact.payload();
        if (payload == null) {
            throw invalidField("payload", "不能为空");
        }
        RollbackPoint rollbackPoint = new RollbackPoint(
                requireExactText(payload, "schemaVersion", SCHEMA_VERSION),
                requireExactText(payload, "provider", PROVIDER),
                requireText(payload.get("status"), "status", false),
                requirePositiveLong(payload.get("appId"), "appId"),
                requireText(payload.get("taskId"), "taskId", false),
                requireText(payload.get("snapshotName"), "snapshotName", true),
                requireText(payload.get("snapshotPath"), "snapshotPath", true),
                requireText(payload.get("projectPath"), "projectPath", true),
                requireText(payload.get("sourceType"), "sourceType", true),
                requireText(payload.get("targetType"), "targetType", true),
                requireNonNegativeInteger(payload.get("fileCount"), "fileCount"),
                requireText(payload.get("reason"), "reason", true),
                requireCreatedAt(payload.get("createdAt"))
        );
        rollbackPoint.validateContext(expectedAppId, expectedTaskId);
        rollbackPoint.validateState();
        return rollbackPoint;
    }

    /** 转换为统一角色与标题的可持久制品。 */
    public GenerationArtifact toArtifact() {
        validateState();
        return GenerationArtifact.of(KEY, ROLE, TITLE, toPayload());
    }

    public boolean created() {
        return STATUS_CREATED.equals(status);
    }

    private void validateContext(Long expectedAppId, String expectedTaskId) {
        if (expectedAppId != null && !Objects.equals(expectedAppId, appId)) {
            throw invalidField("appId", "与当前任务上下文不一致");
        }
        if (StrUtil.isNotBlank(expectedTaskId) && !expectedTaskId.trim().equals(taskId)) {
            throw invalidField("taskId", "与当前任务上下文不一致");
        }
    }

    private void validateState() {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw invalidField("schemaVersion", "不受支持: " + schemaVersion);
        }
        if (!PROVIDER.equals(provider)) {
            throw invalidField("provider", "不受支持: " + provider);
        }
        if (appId == null || appId <= 0) {
            throw invalidField("appId", "必须为正整数");
        }
        if (StrUtil.isBlank(taskId)) {
            throw invalidField("taskId", "不能为空");
        }
        if (fileCount < 0) {
            throw invalidField("fileCount", "必须为非负整数");
        }
        if (!STATUS_CREATED.equals(status) && !STATUS_SKIPPED.equals(status)) {
            throw invalidField("status", "不受支持: " + status);
        }
        if (created()) {
            requireCreatedText(snapshotName, "snapshotName");
            requireCreatedText(snapshotPath, "snapshotPath");
            requireCreatedText(projectPath, "projectPath");
            requireKnownCodeGenType(sourceType, "sourceType");
            requireKnownCodeGenType(targetType, "targetType");
            if (StrUtil.isNotBlank(reason)) {
                throw invalidField("reason", "在 created 状态下必须为空");
            }
            return;
        }
        if (fileCount != 0) {
            throw invalidField("fileCount", "在 skipped 状态下必须为 0");
        }
        if (StrUtil.isNotBlank(snapshotName) || StrUtil.isNotBlank(snapshotPath)) {
            throw invalidField("snapshotPath", "在 skipped 状态下不能携带快照事实");
        }
        if (StrUtil.isBlank(reason)) {
            throw invalidField("reason", "在 skipped 状态下不能为空");
        }
    }

    private static void requireCreatedText(String value, String fieldName) {
        if (StrUtil.isBlank(value)) {
            throw invalidField(fieldName, "在 created 状态下不能为空");
        }
    }

    private static void requireKnownCodeGenType(String value, String fieldName) {
        requireCreatedText(value, fieldName);
        if (CodeGenTypeEnum.getEnumByValue(value) == null) {
            throw invalidField(fieldName, "不是支持的项目类型: " + value);
        }
    }

    private static String requireExactText(Map<String, Object> payload,
                                           String fieldName,
                                           String expected) {
        String actual = requireText(payload.get(fieldName), fieldName, false);
        if (!expected.equals(actual)) {
            throw invalidField(fieldName, "不受支持: " + actual);
        }
        return actual;
    }

    private static String requireText(Object value, String fieldName, boolean allowBlank) {
        if (!(value instanceof String text)) {
            throw invalidField(fieldName, "必须为字符串");
        }
        String normalized = text.trim();
        if (!allowBlank && normalized.isBlank()) {
            throw invalidField(fieldName, "不能为空");
        }
        return normalized;
    }

    private static long requireLong(Object value, String fieldName) {
        if (!(value instanceof Number number)) {
            throw invalidField(fieldName, "必须为整数");
        }
        long parsed = number.longValue();
        if (number.doubleValue() != (double) parsed) {
            throw invalidField(fieldName, "必须为整数");
        }
        return parsed;
    }

    private static Long requirePositiveLong(Object value, String fieldName) {
        long parsed = requireLong(value, fieldName);
        if (parsed <= 0) {
            throw invalidField(fieldName, "必须为正整数");
        }
        return parsed;
    }

    private static int requireNonNegativeInteger(Object value, String fieldName) {
        long parsed = requireLong(value, fieldName);
        if (parsed < 0 || parsed > Integer.MAX_VALUE) {
            throw invalidField(fieldName, "必须为非负整数");
        }
        return (int) parsed;
    }

    private static LocalDateTime requireCreatedAt(Object value) {
        if (value instanceof LocalDateTime createdAt) {
            return createdAt;
        }
        String text = requireText(value, "createdAt", false);
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException exception) {
            throw invalidField("createdAt", "不是有效时间");
        }
    }

    private static IllegalArgumentException invalidField(String fieldName, String reason) {
        return new IllegalArgumentException("回滚点制品字段 " + fieldName + reason);
    }
}
