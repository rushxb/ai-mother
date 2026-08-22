package com.rush.rushaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 标准化变更计划契约。
 */
public record ChangePlan(
        String schemaVersion,
        String changeScope,
        List<String> addFiles,
        List<String> modifyFiles,
        List<String> deleteFiles,
        List<String> impactedModules,
        String validationLevel,
        String rollbackStrategy
) {

    public static final String KEY = "change_plan";

    private static final Pattern CHANGE_SCOPE_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    private static final Pattern WINDOWS_ABSOLUTE_PATH_PATTERN = Pattern.compile("^[a-zA-Z]:/.*");

    private static final Set<String> SUPPORTED_VALIDATION_LEVELS = Set.of(
            "review_only", "build_validation", "validate_light", "build_light", "full_build"
    );

    /** 创建{@code Change}计划实例并完成必要的依赖和初始状态设置。 */
    public ChangePlan {
        schemaVersion = StrUtil.blankToDefault(schemaVersion, "v1");
        changeScope = StrUtil.blankToDefault(changeScope, "unspecified");
        addFiles = normalizeFilePaths(addFiles);
        modifyFiles = normalizeFilePaths(modifyFiles);
        deleteFiles = normalizeFilePaths(deleteFiles);
        impactedModules = normalizeNames(impactedModules);
        validationLevel = StrUtil.blankToDefault(validationLevel, "review_only");
        rollbackStrategy = StrUtil.blankToDefault(rollbackStrategy, "manual_retry_without_snapshot");
    }

    /**
 * 根据输入数据创建当前对象。
 *
 * @param payload 载荷
 * @return {@code Change}计划
 */
    public static ChangePlan fromPayload(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        return new ChangePlan(
                stringValue(payload.get("schemaVersion")),
                stringValue(payload.get("changeScope")),
                listValue(payload.get("addFiles")),
                listValue(payload.get("modifyFiles")),
                listValue(payload.get("deleteFiles")),
                listValue(payload.get("impactedModules")),
                stringValue(payload.get("validationLevel")),
                stringValue(payload.get("rollbackStrategy"))
        );
    }

    /**
     * 从持久化制品恢复规范化变更计划。
     *
     * <p>持久检查点会跨进程、跨版本恢复，并最终参与工具写入授权。这里采用严格类型与
     * 规范路径校验，禁止通过默认值、字符串强转或静默丢弃非法路径把损坏制品变成合法计划。</p>
     */
    public static ChangePlan fromArtifact(GenerationArtifact artifact) {
        if (artifact == null) {
            throw invalidField("artifact", "制品不能为空");
        }
        if (!KEY.equals(artifact.key())) {
            throw invalidField("key", "制品类型不匹配: " + artifact.key());
        }
        Map<String, Object> payload = artifact.payload();
        if (payload == null) {
            throw invalidField("payload", "载荷不能为空");
        }
        String schemaVersion = requireText(payload.get("schemaVersion"), "schemaVersion");
        if (!"v1".equals(schemaVersion)) {
            throw invalidField("schemaVersion", "不受支持: " + schemaVersion);
        }
        String changeScope = requireText(payload.get("changeScope"), "changeScope");
        if (!CHANGE_SCOPE_PATTERN.matcher(changeScope).matches()) {
            throw invalidField("changeScope", "格式不合法: " + changeScope);
        }
        String validationLevel = requireText(payload.get("validationLevel"), "validationLevel");
        if (!SUPPORTED_VALIDATION_LEVELS.contains(validationLevel)) {
            throw invalidField("validationLevel", "不受支持: " + validationLevel);
        }
        return new ChangePlan(
                schemaVersion,
                changeScope,
                requireCanonicalFileList(payload.get("addFiles"), "addFiles"),
                requireCanonicalFileList(payload.get("modifyFiles"), "modifyFiles"),
                requireCanonicalFileList(payload.get("deleteFiles"), "deleteFiles"),
                requireCanonicalNameList(payload.get("impactedModules"), "impactedModules"),
                validationLevel,
                requireText(payload.get("rollbackStrategy"), "rollbackStrategy")
        );
    }

    /**
 * 将当前对象转换为载荷。
 *
 * @return 载荷集合
 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", schemaVersion);
        payload.put("changeScope", changeScope);
        payload.put("addFiles", addFiles);
        payload.put("modifyFiles", modifyFiles);
        payload.put("deleteFiles", deleteFiles);
        payload.put("impactedModules", impactedModules);
        payload.put("validationLevel", validationLevel);
        payload.put("rollbackStrategy", rollbackStrategy);
        return payload;
    }

    /** 以统一 key 写入可恢复的 DAG 制品。 */
    public GenerationArtifact toArtifact(String role, String title) {
        return GenerationArtifact.of(
                KEY,
                requireText(role, "role"),
                requireText(title, "title"),
                toPayload()
        );
    }

    public boolean hasFileChanges() {
        return !addFiles.isEmpty() || !modifyFiles.isEmpty() || !deleteFiles.isEmpty();
    }

    /**
 * 校验并返回有效的{@code s}快照回滚。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean requiresSnapshotRollback() {
        return rollbackStrategy.contains("snapshot") && !rollbackStrategy.contains("without_snapshot");
    }

    /**
     * 是否允许工具在显式文件计划之外创建完整工程。
     *
     * <p>该权限只能由规范化的 bootstrap 计划派生，调用方不得再次解释 scope 字符串。</p>
     */
    public boolean allowsUnplannedWrite() {
        return isProjectBootstrap();
    }

    /** 返回计划内新增、修改和删除文件总数。 */
    public int fileChangeCount() {
        return addFiles.size() + modifyFiles.size() + deleteFiles.size();
    }

    /** 校验变更计划与同一检查点中的代码生成规范是否一致。 */
    public List<String> validateAgainst(GenerationSpecificationArtifact specification) {
        Objects.requireNonNull(specification, "代码生成规范不能为空");
        if (specification.patchFirst()) {
            return validateForPatchFirst(
                    specification.requiresBuild(),
                    specification.validationMode()
            );
        }
        List<String> blockers = new java.util.ArrayList<>();
        if (!isProjectBootstrap()) {
            blockers.add("完整生成规范必须声明 project_bootstrap 变更计划");
        }
        if (!specification.validationMode().equals(validationLevel)) {
            blockers.add("变更计划 validationLevel 与生成规范不一致");
        }
        return List.copyOf(blockers);
    }

    /**
 * 校验{@code ate}{@code For}补丁{@code First}是否有效。
 *
 * @param requiresBuild {@code requiresBuild} 对应的调用参数
 * @param expectedValidationLevel {@code expectedValidationLevel} 对应的调用参数
 * @return {@code ate}{@code For}补丁{@code First}集合
 */
    public List<String> validateForPatchFirst(boolean requiresBuild, String expectedValidationLevel) {
        List<String> blockers = new java.util.ArrayList<>();
        if (!"v1".equals(schemaVersion)) {
            blockers.add("变更计划 schemaVersion 不受支持: " + schemaVersion);
        }
        if (isProjectBootstrap()) {
            blockers.add("patch-first 变更计划不能声明 project_bootstrap 写入范围");
        } else if (!hasFileChanges()) {
            blockers.add("变更计划缺少新增、修改或删除文件范围");
        }
        if (!SUPPORTED_VALIDATION_LEVELS.contains(validationLevel)) {
            blockers.add("变更计划 validationLevel 不受支持: " + validationLevel);
        }
        if (StrUtil.isNotBlank(expectedValidationLevel) && !expectedValidationLevel.equals(validationLevel)) {
            blockers.add("变更计划 validationLevel 与生成规范不一致");
        }
        if (requiresBuild && !requiresSnapshotRollback()) {
            blockers.add("构建校验场景必须声明可回滚到快照或稳定版本的策略");
        }
        return blockers;
    }

    /**
 * 规范化文件{@code Paths}。
 *
 * @param paths 待处理的 {@code paths} 集合
 * @return 文件{@code Paths}集合
 */
    public static List<String> normalizeFilePaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        return paths.stream()
                .filter(StrUtil::isNotBlank)
                .map(path -> path.replace("\\", "/").trim())
                .filter(ChangePlan::isWorkspaceRelativePath)
                .distinct()
                .toList();
    }

    /** 变更计划只接受工作区内相对路径，避免持久制品绕过写入边界。 */
    private static boolean isWorkspaceRelativePath(String path) {
        return !path.startsWith("/")
                && !WINDOWS_ABSOLUTE_PATH_PATTERN.matcher(path).matches()
                && !path.contains("..");
    }

    private boolean isProjectBootstrap() {
        return "project_bootstrap".equals(changeScope);
    }

    private static List<String> normalizeNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return names.stream()
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> requireCanonicalFileList(Object value, String fieldName) {
        List<String> values = requireStringList(value, fieldName);
        List<String> normalized = normalizeFilePaths(values);
        if (!normalized.equals(values)) {
            throw invalidField(fieldName, "必须为去重后的规范相对路径，且不能包含绝对路径或上级目录");
        }
        return normalized;
    }

    private static List<String> requireCanonicalNameList(Object value, String fieldName) {
        List<String> values = requireStringList(value, fieldName);
        List<String> normalized = normalizeNames(values);
        if (!normalized.equals(values)) {
            throw invalidField(fieldName, "必须为去重后的非空名称列表");
        }
        return normalized;
    }

    private static List<String> requireStringList(Object value, String fieldName) {
        if (!(value instanceof List<?> values)) {
            throw invalidField(fieldName, "必须为字符串列表");
        }
        List<String> result = new java.util.ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw invalidField(fieldName, "只能包含非空字符串");
            }
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    private static String requireText(Object value, String fieldName) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalidField(fieldName, "必须为非空字符串");
        }
        return text.trim();
    }

    private static IllegalArgumentException invalidField(String fieldName, String reason) {
        return new IllegalArgumentException("变更计划制品字段 " + fieldName + ": " + reason);
    }

    /** 列出符合条件的值。 */
    private static List<String> listValue(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(item -> item != null && StrUtil.isNotBlank(String.valueOf(item)))
                    .map(String::valueOf)
                    .toList();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            return List.of(text);
        }
        return List.of();
    }
}
