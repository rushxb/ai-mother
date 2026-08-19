package com.rush.rushaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 生成后差异摘要契约。
 */
public record DiffSummary(
        String schemaVersion,
        String provider,
        String status,
        Long appId,
        String taskId,
        String basePath,
        String currentPath,
        int addedCount,
        int modifiedCount,
        int deletedCount,
        List<String> addedFiles,
        List<String> modifiedFiles,
        List<String> deletedFiles,
        List<String> modifiedDetails,
        String reason,
        LocalDateTime createdAt
) {

    public static final String KEY = "diff_summary";

    private static final String SCHEMA_VERSION = "v1";
    private static final String PROVIDER = "local_snapshot";
    private static final String STATUS_CREATED = "created";
    private static final String STATUS_SKIPPED = "skipped";
    private static final String ROLE = "Orchestrator";
    private static final String TITLE = "生成后差异摘要";

    /** 创建{@code Diff}汇总实例并完成必要的依赖和初始状态设置。 */
    public DiffSummary {
        schemaVersion = StrUtil.blankToDefault(schemaVersion, "v1");
        provider = StrUtil.blankToDefault(provider, "local_snapshot");
        status = StrUtil.blankToDefault(status, "skipped");
        addedFiles = addedFiles == null ? List.of() : List.copyOf(addedFiles);
        modifiedFiles = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
        deletedFiles = deletedFiles == null ? List.of() : List.copyOf(deletedFiles);
        modifiedDetails = modifiedDetails == null ? List.of() : List.copyOf(modifiedDetails);
        reason = StrUtil.blankToDefault(reason, "");
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    /**
 * 创建{@code d}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param basePath 基础路径
 * @param currentPath 当前路径
 * @param addedFiles 待处理的 {@code addedFiles} 集合
 * @param modifiedFiles 待处理的 {@code modifiedFiles} 集合
 * @param deletedFiles 待处理的 {@code deletedFiles} 集合
 * @param modifiedDetails 待处理的 {@code modifiedDetails} 集合
 * @return {@code d}
 */
    public static DiffSummary created(Long appId,
                                      String taskId,
                                      String basePath,
                                      String currentPath,
                                      List<String> addedFiles,
                                      List<String> modifiedFiles,
                                      List<String> deletedFiles,
                                      List<String> modifiedDetails) {
        return new DiffSummary(
                SCHEMA_VERSION,
                PROVIDER,
                STATUS_CREATED,
                appId,
                taskId,
                basePath,
                currentPath,
                addedFiles == null ? 0 : addedFiles.size(),
                modifiedFiles == null ? 0 : modifiedFiles.size(),
                deletedFiles == null ? 0 : deletedFiles.size(),
                addedFiles,
                modifiedFiles,
                deletedFiles,
                modifiedDetails,
                "",
                LocalDateTime.now()
        );
    }

    /**
 * 返回{@code skipped}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param basePath 基础路径
 * @param currentPath 当前路径
 * @param reason 原因
 * @return {@code Diff}汇总
 */
    public static DiffSummary skipped(Long appId,
                                      String taskId,
                                      String basePath,
                                      String currentPath,
                                      String reason) {
        return new DiffSummary(
                SCHEMA_VERSION,
                PROVIDER,
                STATUS_SKIPPED,
                appId,
                taskId,
                basePath,
                currentPath,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                reason,
                LocalDateTime.now()
        );
    }

    /**
     * 从通用持久制品恢复可信差异事实。
     *
     * <p>该入口集中校验状态与计数不变量，防止跳过、串任务或损坏的检查点被误判为有效变更。</p>
     */
    public static DiffSummary fromArtifact(GenerationArtifact artifact,
                                           Long expectedAppId,
                                           String expectedTaskId) {
        Objects.requireNonNull(artifact, "差异摘要制品不能为空");
        if (!KEY.equals(artifact.key())) {
            throw new IllegalArgumentException(
                    "制品类型不匹配，期望: " + KEY + "，实际: " + artifact.key());
        }
        Map<String, Object> payload = Objects.requireNonNull(
                artifact.payload(), "差异摘要制品载荷不能为空");
        DiffSummary summary = new DiffSummary(
                requireExactText(payload, "schemaVersion", SCHEMA_VERSION),
                requireText(payload.get("provider"), "provider", false),
                requireStatus(payload.get("status")),
                optionalPositiveLong(payload.get("appId"), "appId"),
                requireText(payload.get("taskId"), "taskId", true),
                requireText(payload.get("basePath"), "basePath", true),
                requireText(payload.get("currentPath"), "currentPath", true),
                requireNonNegativeInteger(payload.get("addedCount"), "addedCount"),
                requireNonNegativeInteger(payload.get("modifiedCount"), "modifiedCount"),
                requireNonNegativeInteger(payload.get("deletedCount"), "deletedCount"),
                requireStringList(payload.get("addedFiles"), "addedFiles"),
                requireStringList(payload.get("modifiedFiles"), "modifiedFiles"),
                requireStringList(payload.get("deletedFiles"), "deletedFiles"),
                requireStringList(payload.get("modifiedDetails"), "modifiedDetails"),
                requireText(payload.get("reason"), "reason", true),
                requireCreatedAt(payload.get("createdAt"))
        );
        summary.validateContext(expectedAppId, expectedTaskId);
        summary.validateState();
        return summary;
    }

    /** 从通用持久制品恢复差异事实，任务上下文由调用方在后续显式核对。 */
    public static DiffSummary fromArtifact(GenerationArtifact artifact) {
        return fromArtifact(artifact, null, null);
    }

    /** 仅已成功生成且至少包含一个变更文件时，才构成工作区变更证据。 */
    public boolean hasChanges() {
        return STATUS_CREATED.equals(status) && changedFileCount() > 0;
    }

    /** 返回已校验的新增、修改和删除文件总数。 */
    public int changedFileCount() {
        try {
            return Math.addExact(Math.addExact(addedCount, modifiedCount), deletedCount);
        } catch (ArithmeticException exception) {
            throw invalidField("变更计数", "总和超过整数上限");
        }
    }

    /** 差异摘要是否由快照比较成功生成。 */
    public boolean created() {
        return STATUS_CREATED.equals(status);
    }

    /** 判断该摘要是否属于当前应用和任务。 */
    public boolean matchesContext(Long expectedAppId, String expectedTaskId) {
        return Objects.equals(expectedAppId, appId)
                && Objects.equals(expectedTaskId, taskId);
    }

    /** 转换为统一角色与标题的可持久制品。 */
    public GenerationArtifact toArtifact() {
        return GenerationArtifact.of(KEY, ROLE, TITLE, toPayload());
    }

    /**
 * 将当前对象转换为载荷。
 *
 * @return 载荷集合
 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", schemaVersion);
        payload.put("provider", provider);
        payload.put("status", status);
        payload.put("appId", appId);
        payload.put("taskId", taskId);
        payload.put("basePath", basePath);
        payload.put("currentPath", currentPath);
        payload.put("addedCount", addedCount);
        payload.put("modifiedCount", modifiedCount);
        payload.put("deletedCount", deletedCount);
        payload.put("addedFiles", addedFiles);
        payload.put("modifiedFiles", modifiedFiles);
        payload.put("deletedFiles", deletedFiles);
        payload.put("modifiedDetails", modifiedDetails);
        payload.put("reason", reason);
        payload.put("createdAt", createdAt.toString());
        return payload;
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
        if (STATUS_CREATED.equals(status)) {
            if (StrUtil.isBlank(currentPath)) {
                throw invalidField("currentPath", "在 created 状态下不能为空");
            }
            requireCountMatches("addedCount", addedCount, addedFiles);
            requireCountMatches("modifiedCount", modifiedCount, modifiedFiles);
            requireCountMatches("deletedCount", deletedCount, deletedFiles);
            return;
        }
        if (changedFileCount() != 0
                || !addedFiles.isEmpty()
                || !modifiedFiles.isEmpty()
                || !deletedFiles.isEmpty()
                || !modifiedDetails.isEmpty()) {
            throw invalidField("status", "为 skipped 时不能携带变更结果");
        }
        if (StrUtil.isBlank(reason)) {
            throw invalidField("reason", "在 skipped 状态下不能为空");
        }
    }

    private static void requireCountMatches(String fieldName, int count, List<String> files) {
        if (count != files.size()) {
            throw invalidField(fieldName, "与对应文件列表长度不一致");
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

    private static String requireStatus(Object value) {
        String status = requireText(value, "status", false);
        if (!STATUS_CREATED.equals(status) && !STATUS_SKIPPED.equals(status)) {
            throw invalidField("status", "不受支持: " + status);
        }
        return status;
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

    private static Long optionalLong(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw invalidField(fieldName, "必须为整数");
        }
        long longValue = number.longValue();
        if (number.doubleValue() != (double) longValue) {
            throw invalidField(fieldName, "必须为整数");
        }
        return longValue;
    }

    private static Long optionalPositiveLong(Object value, String fieldName) {
        Long parsed = optionalLong(value, fieldName);
        if (parsed != null && parsed <= 0) {
            throw invalidField(fieldName, "必须为正整数");
        }
        return parsed;
    }

    private static int requireNonNegativeInteger(Object value, String fieldName) {
        Long parsed = optionalLong(value, fieldName);
        if (parsed == null || parsed < 0 || parsed > Integer.MAX_VALUE) {
            throw invalidField(fieldName, "必须为非负整数");
        }
        return parsed.intValue();
    }

    private static List<String> requireStringList(Object value, String fieldName) {
        if (!(value instanceof List<?> values)) {
            throw invalidField(fieldName, "必须为字符串列表");
        }
        return values.stream()
                .map(item -> requireText(item, fieldName, false))
                .toList();
    }

    private static LocalDateTime requireCreatedAt(Object value) {
        if (value instanceof LocalDateTime createdAt) {
            return createdAt;
        }
        String text = requireText(value, "createdAt", false);
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException exception) {
            throw invalidField("createdAt", "不是有效的时间");
        }
    }

    private static IllegalArgumentException invalidField(String fieldName, String reason) {
        return new IllegalArgumentException("差异摘要制品字段 " + fieldName + reason);
    }
}
