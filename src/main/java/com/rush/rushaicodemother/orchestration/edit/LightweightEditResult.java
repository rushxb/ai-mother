package com.rush.rushaicodemother.orchestration.edit;

import java.util.List;

/**
 * 轻量编辑执行结果。
 */
public record LightweightEditResult(
        String taskId,
        String route,
        String summary,
        List<String> appliedOperations,
        String validationResult
) {
}
