package com.rush.rushaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        if (!isProjectBootstrap() && !hasFileChanges()) {
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
                .filter(path -> !path.startsWith("/") && !path.contains(".."))
                .distinct()
                .toList();
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
