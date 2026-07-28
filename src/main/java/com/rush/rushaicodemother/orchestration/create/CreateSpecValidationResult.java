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
    /**
 * 返回{@code ok}。
 *
 * @param warnings 待处理的 {@code warnings} 集合
 * @return 创建{@code Spec}校验结果
 */
    public static CreateSpecValidationResult ok(List<String> warnings) {
        return new CreateSpecValidationResult(true, warnings == null ? List.of() : List.copyOf(warnings), List.of());
    }
}
