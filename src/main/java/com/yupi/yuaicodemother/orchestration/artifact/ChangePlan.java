package com.yupi.yuaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public ChangePlan {
        schemaVersion = StrUtil.blankToDefault(schemaVersion, "v1");
        changeScope = StrUtil.blankToDefault(changeScope, "unspecified");
        addFiles = addFiles == null ? List.of() : List.copyOf(addFiles);
        modifyFiles = modifyFiles == null ? List.of() : List.copyOf(modifyFiles);
        deleteFiles = deleteFiles == null ? List.of() : List.copyOf(deleteFiles);
        impactedModules = impactedModules == null ? List.of() : List.copyOf(impactedModules);
        validationLevel = StrUtil.blankToDefault(validationLevel, "review_only");
        rollbackStrategy = StrUtil.blankToDefault(rollbackStrategy, "manual_retry_without_snapshot");
    }

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
}
