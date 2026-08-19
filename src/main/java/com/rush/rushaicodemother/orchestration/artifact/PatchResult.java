package com.rush.rushaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Patch-first 实际落盘结果契约。
 */
public record PatchResult(
        String schemaVersion,
        String provider,
        String status,
        Long appId,
        String taskId,
        List<String> plannedAddFiles,
        List<String> plannedModifyFiles,
        List<String> plannedDeleteFiles,
        List<String> actualAddedFiles,
        List<String> actualModifiedFiles,
        List<String> actualDeletedFiles,
        List<String> unplannedFiles,
        List<String> missingPlannedFiles,
        int matchedFileCount,
        int unplannedFileCount,
        int missingPlannedFileCount,
        String reason,
        LocalDateTime createdAt
) {

    public static final String KEY = "patch_result";

    private static final String SCHEMA_VERSION = "v1";
    private static final String PROVIDER = "local_diff";
    private static final String STATUS_APPLIED = "applied";
    private static final String STATUS_DRIFTED = "drifted";
    private static final String STATUS_SKIPPED = "skipped";
    private static final String ROLE = "Orchestrator";
    private static final String TITLE = "Patch 实际落盘结果";

    /** 创建补丁结果实例并完成必要的依赖和初始状态设置。 */
    public PatchResult {
        schemaVersion = StrUtil.blankToDefault(schemaVersion, SCHEMA_VERSION);
        provider = StrUtil.blankToDefault(provider, PROVIDER);
        status = StrUtil.blankToDefault(status, STATUS_SKIPPED);
        plannedAddFiles = plannedAddFiles == null ? List.of() : List.copyOf(plannedAddFiles);
        plannedModifyFiles = plannedModifyFiles == null ? List.of() : List.copyOf(plannedModifyFiles);
        plannedDeleteFiles = plannedDeleteFiles == null ? List.of() : List.copyOf(plannedDeleteFiles);
        actualAddedFiles = actualAddedFiles == null ? List.of() : List.copyOf(actualAddedFiles);
        actualModifiedFiles = actualModifiedFiles == null ? List.of() : List.copyOf(actualModifiedFiles);
        actualDeletedFiles = actualDeletedFiles == null ? List.of() : List.copyOf(actualDeletedFiles);
        unplannedFiles = unplannedFiles == null ? List.of() : List.copyOf(unplannedFiles);
        missingPlannedFiles = missingPlannedFiles == null ? List.of() : List.copyOf(missingPlannedFiles);
        reason = StrUtil.blankToDefault(reason, "");
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    /**
 * 创建{@code d}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param changePlan {@code changePlan} 对应的调用参数
 * @param actualAddedFiles 待处理的 {@code actualAddedFiles} 集合
 * @param actualModifiedFiles 待处理的 {@code actualModifiedFiles} 集合
 * @param actualDeletedFiles 待处理的 {@code actualDeletedFiles} 集合
 * @param unplannedFiles 待处理的 {@code unplannedFiles} 集合
 * @param missingPlannedFiles 待处理的 {@code missingPlannedFiles} 集合
 * @return {@code d}
 */
    public static PatchResult created(Long appId,
                                      String taskId,
                                      ChangePlan changePlan,
                                      List<String> actualAddedFiles,
                                      List<String> actualModifiedFiles,
                                      List<String> actualDeletedFiles,
                                      List<String> unplannedFiles,
                                      List<String> missingPlannedFiles) {
        return new PatchResult(
                SCHEMA_VERSION,
                PROVIDER,
                unplannedFiles.isEmpty() && missingPlannedFiles.isEmpty() ? STATUS_APPLIED : STATUS_DRIFTED,
                appId,
                taskId,
                changePlan.addFiles(),
                changePlan.modifyFiles(),
                changePlan.deleteFiles(),
                actualAddedFiles,
                actualModifiedFiles,
                actualDeletedFiles,
                unplannedFiles,
                missingPlannedFiles,
                actualAddedFiles.size() + actualModifiedFiles.size() + actualDeletedFiles.size() - unplannedFiles.size(),
                unplannedFiles.size(),
                missingPlannedFiles.size(),
                unplannedFiles.isEmpty() && missingPlannedFiles.isEmpty() ? "" : "actual_diff_outside_change_plan",
                LocalDateTime.now()
        );
    }

    /**
 * 返回{@code skipped}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param reason 原因
 * @return 补丁结果
 */
    public static PatchResult skipped(Long appId, String taskId, String reason) {
        return new PatchResult(
                SCHEMA_VERSION,
                PROVIDER,
                STATUS_SKIPPED,
                appId,
                taskId,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                0,
                0,
                reason,
                LocalDateTime.now()
        );
    }

    /**
     * 从通用持久制品恢复可信补丁事实。
     *
     * <p>除字段类型外，该入口还会重新计算计划外文件、缺失文件和匹配计数，避免仅伪造
     * {@code status=applied} 就被用户事件或长期记忆当成真实落盘成功。</p>
     */
    public static PatchResult fromArtifact(GenerationArtifact artifact,
                                           Long expectedAppId,
                                           String expectedTaskId) {
        Objects.requireNonNull(artifact, "补丁结果制品不能为空");
        if (!KEY.equals(artifact.key())) {
            throw invalidField("key", "制品类型不匹配: " + artifact.key());
        }
        Map<String, Object> payload = Objects.requireNonNull(
                artifact.payload(), "补丁结果制品载荷不能为空");
        PatchResult result = new PatchResult(
                requireExactText(payload, "schemaVersion", SCHEMA_VERSION),
                requireText(payload.get("provider"), "provider", false),
                requireStatus(payload.get("status")),
                requirePositiveLong(payload.get("appId"), "appId"),
                requireText(payload.get("taskId"), "taskId", false),
                requireStringList(payload.get("plannedAddFiles"), "plannedAddFiles"),
                requireStringList(payload.get("plannedModifyFiles"), "plannedModifyFiles"),
                requireStringList(payload.get("plannedDeleteFiles"), "plannedDeleteFiles"),
                requireStringList(payload.get("actualAddedFiles"), "actualAddedFiles"),
                requireStringList(payload.get("actualModifiedFiles"), "actualModifiedFiles"),
                requireStringList(payload.get("actualDeletedFiles"), "actualDeletedFiles"),
                requireStringList(payload.get("unplannedFiles"), "unplannedFiles"),
                requireStringList(payload.get("missingPlannedFiles"), "missingPlannedFiles"),
                requireNonNegativeInteger(payload.get("matchedFileCount"), "matchedFileCount"),
                requireNonNegativeInteger(payload.get("unplannedFileCount"), "unplannedFileCount"),
                requireNonNegativeInteger(payload.get("missingPlannedFileCount"), "missingPlannedFileCount"),
                requireText(payload.get("reason"), "reason", true),
                requireCreatedAt(payload.get("createdAt"))
        );
        result.validateContext(expectedAppId, expectedTaskId);
        result.validateState();
        return result;
    }

    /** 从持久制品恢复补丁事实，任务上下文由调用方在后续显式核对。 */
    public static PatchResult fromArtifact(GenerationArtifact artifact) {
        return fromArtifact(artifact, null, null);
    }

    /** 补丁结果是否与变更计划完全对齐。 */
    public boolean applied() {
        return STATUS_APPLIED.equals(status);
    }

    /** 补丁结果是否存在计划与实际落盘偏差。 */
    public boolean drifted() {
        return STATUS_DRIFTED.equals(status);
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
        payload.put("plannedAddFiles", plannedAddFiles);
        payload.put("plannedModifyFiles", plannedModifyFiles);
        payload.put("plannedDeleteFiles", plannedDeleteFiles);
        payload.put("actualAddedFiles", actualAddedFiles);
        payload.put("actualModifiedFiles", actualModifiedFiles);
        payload.put("actualDeletedFiles", actualDeletedFiles);
        payload.put("unplannedFiles", unplannedFiles);
        payload.put("missingPlannedFiles", missingPlannedFiles);
        payload.put("matchedFileCount", matchedFileCount);
        payload.put("unplannedFileCount", unplannedFileCount);
        payload.put("missingPlannedFileCount", missingPlannedFileCount);
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
        requireDistinct(plannedAddFiles, "plannedAddFiles");
        requireDistinct(plannedModifyFiles, "plannedModifyFiles");
        requireDistinct(plannedDeleteFiles, "plannedDeleteFiles");
        requireDistinct(actualAddedFiles, "actualAddedFiles");
        requireDistinct(actualModifiedFiles, "actualModifiedFiles");
        requireDistinct(actualDeletedFiles, "actualDeletedFiles");

        List<String> expectedUnplannedFiles = new ArrayList<>();
        expectedUnplannedFiles.addAll(outsidePlan("add", actualAddedFiles, plannedAddFiles));
        expectedUnplannedFiles.addAll(outsidePlan("modify", actualModifiedFiles, plannedModifyFiles));
        expectedUnplannedFiles.addAll(outsidePlan("delete", actualDeletedFiles, plannedDeleteFiles));
        requireExactList("unplannedFiles", unplannedFiles, expectedUnplannedFiles);

        List<String> expectedMissingPlannedFiles = new ArrayList<>();
        expectedMissingPlannedFiles.addAll(missingFromActual("add", plannedAddFiles, actualAddedFiles));
        expectedMissingPlannedFiles.addAll(missingFromActual("modify", plannedModifyFiles, actualModifiedFiles));
        expectedMissingPlannedFiles.addAll(missingFromActual("delete", plannedDeleteFiles, actualDeletedFiles));
        requireExactList("missingPlannedFiles", missingPlannedFiles, expectedMissingPlannedFiles);

        requireCountMatches("unplannedFileCount", unplannedFileCount, unplannedFiles);
        requireCountMatches("missingPlannedFileCount", missingPlannedFileCount, missingPlannedFiles);
        int actualFileCount;
        try {
            actualFileCount = Math.addExact(
                    Math.addExact(actualAddedFiles.size(), actualModifiedFiles.size()),
                    actualDeletedFiles.size());
        } catch (ArithmeticException overflow) {
            throw invalidField("matchedFileCount", "实际文件总数超过整数上限");
        }
        if (matchedFileCount != actualFileCount - unplannedFileCount) {
            throw invalidField("matchedFileCount", "与计划内实际文件数量不一致");
        }

        boolean hasDrift = !unplannedFiles.isEmpty() || !missingPlannedFiles.isEmpty();
        if (STATUS_APPLIED.equals(status)) {
            if (hasDrift) {
                throw invalidField("status", "为 applied 时不能存在落盘偏差");
            }
            if (StrUtil.isNotBlank(reason)) {
                throw invalidField("reason", "在 applied 状态下必须为空");
            }
            return;
        }
        if (STATUS_DRIFTED.equals(status)) {
            if (!hasDrift) {
                throw invalidField("status", "为 drifted 时必须存在落盘偏差");
            }
            if (StrUtil.isBlank(reason)) {
                throw invalidField("reason", "在 drifted 状态下不能为空");
            }
            return;
        }
        if (!allFileFactsEmpty() || matchedFileCount != 0
                || unplannedFileCount != 0 || missingPlannedFileCount != 0) {
            throw invalidField("status", "为 skipped 时不能携带补丁结果");
        }
        if (StrUtil.isBlank(reason)) {
            throw invalidField("reason", "在 skipped 状态下不能为空");
        }
    }

    private boolean allFileFactsEmpty() {
        return plannedAddFiles.isEmpty()
                && plannedModifyFiles.isEmpty()
                && plannedDeleteFiles.isEmpty()
                && actualAddedFiles.isEmpty()
                && actualModifiedFiles.isEmpty()
                && actualDeletedFiles.isEmpty()
                && unplannedFiles.isEmpty()
                && missingPlannedFiles.isEmpty();
    }

    private static List<String> outsidePlan(String kind,
                                            List<String> actualFiles,
                                            List<String> plannedFiles) {
        Set<String> planned = new LinkedHashSet<>(plannedFiles);
        return actualFiles.stream()
                .filter(file -> !planned.contains(file))
                .map(file -> kind + ":" + file)
                .toList();
    }

    private static List<String> missingFromActual(String kind,
                                                  List<String> plannedFiles,
                                                  List<String> actualFiles) {
        Set<String> actual = new LinkedHashSet<>(actualFiles);
        return plannedFiles.stream()
                .filter(file -> !actual.contains(file))
                .map(file -> kind + ":" + file)
                .toList();
    }

    private static void requireExactList(String fieldName,
                                         List<String> actual,
                                         List<String> expected) {
        if (!actual.equals(expected)) {
            throw invalidField(fieldName, "与计划和实际文件重新计算结果不一致");
        }
    }

    private static void requireCountMatches(String fieldName, int count, List<String> files) {
        if (count != files.size()) {
            throw invalidField(fieldName, "与对应文件列表长度不一致");
        }
    }

    private static void requireDistinct(List<String> values, String fieldName) {
        if (new LinkedHashSet<>(values).size() != values.size()) {
            throw invalidField(fieldName, "不能包含重复文件");
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
        if (!STATUS_APPLIED.equals(status)
                && !STATUS_DRIFTED.equals(status)
                && !STATUS_SKIPPED.equals(status)) {
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

    private static long requireLong(Object value, String fieldName) {
        if (!(value instanceof Number number)) {
            throw invalidField(fieldName, "必须为整数");
        }
        long longValue = number.longValue();
        if (number.doubleValue() != (double) longValue) {
            throw invalidField(fieldName, "必须为整数");
        }
        return longValue;
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
        return new IllegalArgumentException("补丁结果制品字段 " + fieldName + reason);
    }
}
