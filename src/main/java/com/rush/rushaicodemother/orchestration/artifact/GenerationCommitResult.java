package com.rush.rushaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 生成结果本地 Git 提交契约。
 */
public record GenerationCommitResult(
        String schemaVersion,
        String provider,
        String status,
        Long appId,
        String taskId,
        String projectPath,
        String commitId,
        String shortCommitId,
        String branch,
        int committedFileCount,
        List<String> committedFiles,
        String reason,
        LocalDateTime createdAt
) {

    public static final String KEY = "generation_commit";

    private static final String SCHEMA_VERSION = "v1";
    private static final String PROVIDER = "local_git";
    private static final String STATUS_COMMITTED = "committed";
    private static final String STATUS_SKIPPED = "skipped";
    private static final String STATUS_FAILED = "failed";
    private static final String ROLE = "Orchestrator";
    private static final String TITLE = "生成结果本地 Git 提交";

    /** 创建生成提交结果实例并完成必要的依赖和初始状态设置。 */
    public GenerationCommitResult {
        schemaVersion = StrUtil.blankToDefault(schemaVersion, SCHEMA_VERSION);
        provider = StrUtil.blankToDefault(provider, PROVIDER);
        status = StrUtil.blankToDefault(status, STATUS_SKIPPED);
        projectPath = StrUtil.blankToDefault(projectPath, "");
        commitId = StrUtil.blankToDefault(commitId, "");
        shortCommitId = StrUtil.blankToDefault(shortCommitId, "");
        branch = StrUtil.blankToDefault(branch, "");
        committedFiles = committedFiles == null ? List.of() : List.copyOf(committedFiles);
        reason = StrUtil.blankToDefault(reason, "");
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    /**
 * 返回{@code committed}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param projectPath 项目路径
 * @param commitId 提交编号
 * @param branch {@code branch} 对应的调用参数
 * @param committedFiles 待处理的 {@code committedFiles} 集合
 * @return 生成提交结果
 */
    public static GenerationCommitResult committed(Long appId,
                                                   String taskId,
                                                   String projectPath,
                                                   String commitId,
                                                   String branch,
                                                   List<String> committedFiles) {
        String shortCommitId = abbreviateCommitId(commitId);
        return new GenerationCommitResult(
                SCHEMA_VERSION,
                PROVIDER,
                STATUS_COMMITTED,
                appId,
                taskId,
                projectPath,
                commitId,
                shortCommitId,
                branch,
                committedFiles == null ? 0 : committedFiles.size(),
                committedFiles,
                "",
                LocalDateTime.now()
        );
    }

    /**
 * 返回{@code skipped}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param projectPath 项目路径
 * @param commitId 提交编号
 * @param branch {@code branch} 对应的调用参数
 * @param reason 原因
 * @return 生成提交结果
 */
    public static GenerationCommitResult skipped(Long appId,
                                                 String taskId,
                                                 String projectPath,
                                                 String commitId,
                                                 String branch,
                                                 String reason) {
        String shortCommitId = abbreviateCommitId(commitId);
        return new GenerationCommitResult(
                SCHEMA_VERSION,
                PROVIDER,
                STATUS_SKIPPED,
                appId,
                taskId,
                projectPath,
                commitId,
                shortCommitId,
                branch,
                0,
                List.of(),
                reason,
                LocalDateTime.now()
        );
    }

    /**
 * 将{@code ed}标记为失败并记录原因。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param projectPath 项目路径
 * @param commitId 提交编号
 * @param branch {@code branch} 对应的调用参数
 * @param reason 原因
 * @return {@code ed}
 */
    public static GenerationCommitResult failed(Long appId,
                                                String taskId,
                                                String projectPath,
                                                String commitId,
                                                String branch,
                                                String reason) {
        String shortCommitId = abbreviateCommitId(commitId);
        return new GenerationCommitResult(
                SCHEMA_VERSION,
                PROVIDER,
                STATUS_FAILED,
                appId,
                taskId,
                projectPath,
                commitId,
                shortCommitId,
                branch,
                0,
                List.of(),
                reason,
                LocalDateTime.now()
        );
    }

    /**
     * 从持久制品恢复可信提交事实，并拒绝串任务或内部字段互相矛盾的载荷。
     */
    public static GenerationCommitResult fromArtifact(GenerationArtifact artifact,
                                                       Long expectedAppId,
                                                       String expectedTaskId) {
        Objects.requireNonNull(artifact, "生成提交制品不能为空");
        if (!KEY.equals(artifact.key())) {
            throw invalidField("key", "制品类型不匹配: " + artifact.key());
        }
        Map<String, Object> payload = Objects.requireNonNull(
                artifact.payload(), "生成提交制品载荷不能为空");
        GenerationCommitResult result = new GenerationCommitResult(
                requireExactText(payload, "schemaVersion", SCHEMA_VERSION),
                requireExactText(payload, "provider", PROVIDER),
                requireStatus(payload.get("status")),
                requirePositiveLong(payload.get("appId"), "appId"),
                requireText(payload.get("taskId"), "taskId", false),
                requireText(payload.get("projectPath"), "projectPath", true),
                requireText(payload.get("commitId"), "commitId", true),
                requireText(payload.get("shortCommitId"), "shortCommitId", true),
                requireText(payload.get("branch"), "branch", true),
                requireNonNegativeInteger(payload.get("committedFileCount"), "committedFileCount"),
                requireStringList(payload.get("committedFiles"), "committedFiles"),
                requireText(payload.get("reason"), "reason", true),
                requireCreatedAt(payload.get("createdAt"))
        );
        result.validateContext(expectedAppId, expectedTaskId);
        result.validateState();
        return result;
    }

    /** 从持久制品恢复提交事实，调用方无需绑定特定任务上下文。 */
    public static GenerationCommitResult fromArtifact(GenerationArtifact artifact) {
        return fromArtifact(artifact, null, null);
    }

    /** 是否确实生成了本地 Git 提交。 */
    public boolean committed() {
        return STATUS_COMMITTED.equals(status);
    }

    /** 转换为统一 key、角色和标题的持久制品。 */
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
        payload.put("projectPath", projectPath);
        payload.put("commitId", commitId);
        payload.put("shortCommitId", shortCommitId);
        payload.put("branch", branch);
        payload.put("committedFileCount", committedFileCount);
        payload.put("committedFiles", committedFiles);
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
        if (committedFileCount != committedFiles.size()) {
            throw invalidField("committedFileCount", "与提交文件列表长度不一致");
        }
        if (new LinkedHashSet<>(committedFiles).size() != committedFiles.size()) {
            throw invalidField("committedFiles", "不能包含重复文件");
        }
        String expectedShortCommitId = abbreviateCommitId(commitId);
        if (!expectedShortCommitId.equals(shortCommitId)) {
            throw invalidField("shortCommitId", "与完整提交编号不一致");
        }
        if (STATUS_COMMITTED.equals(status)) {
            if (StrUtil.isBlank(projectPath) || StrUtil.isBlank(commitId)) {
                throw invalidField("status", "为 committed 时必须包含项目路径和提交编号");
            }
            if (committedFiles.isEmpty()) {
                throw invalidField("committedFiles", "在 committed 状态下不能为空");
            }
            if (StrUtil.isNotBlank(reason)) {
                throw invalidField("reason", "在 committed 状态下必须为空");
            }
            return;
        }
        if (!committedFiles.isEmpty() || committedFileCount != 0) {
            throw invalidField("status", "为 skipped 或 failed 时不能携带提交文件");
        }
        if (StrUtil.isBlank(reason)) {
            throw invalidField("reason", "在 skipped 或 failed 状态下不能为空");
        }
    }

    private static String abbreviateCommitId(String commitId) {
        return StrUtil.isBlank(commitId)
                ? ""
                : StrUtil.sub(commitId, 0, Math.min(12, commitId.length()));
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
        if (!STATUS_COMMITTED.equals(status)
                && !STATUS_SKIPPED.equals(status)
                && !STATUS_FAILED.equals(status)) {
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
        return new IllegalArgumentException("生成提交制品字段 " + fieldName + ": " + reason);
    }
}
