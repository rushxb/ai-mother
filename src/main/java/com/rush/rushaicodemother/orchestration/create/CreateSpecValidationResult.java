package com.rush.rushaicodemother.orchestration.create;

import java.util.List;

/**
 * 创建规格校验执行结果。
 */
public record CreateSpecValidationResult(
        boolean valid,
        List<String> warnings,
        List<String> errors
) {
    public static CreateSpecValidationResult ok(List<String> warnings) {
        return new CreateSpecValidationResult(true, warnings == null ? List.of() : List.copyOf(warnings), List.of());
    }
}
