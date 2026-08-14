package com.rush.rushaicodemother.orchestration.verification;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 验证器实际执行并通过的步骤。
 *
 * <p>该对象只表达已观测事实，不携带路由期望或验证策略，避免调用方把“计划执行”
 * 错当成“已经通过”。</p>
 */
public record GenerationValidationObservation(
        CodeGenTypeEnum targetType,
        String source,
        Set<GenerationExecutionPlan.ValidationStep> passedSteps,
        Map<String, Object> details
) {

    public GenerationValidationObservation {
        if (targetType == null) {
            throw new IllegalArgumentException("验证目标类型不能为空");
        }
        source = source == null || source.isBlank() ? "generation_validation" : source.trim();
        if (passedSteps == null || passedSteps.isEmpty()) {
            throw new IllegalArgumentException("已通过的验证步骤不能为空");
        }
        passedSteps = Collections.unmodifiableSet(EnumSet.copyOf(passedSteps));
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static GenerationValidationObservation passed(
            CodeGenTypeEnum targetType,
            String source,
            Set<GenerationExecutionPlan.ValidationStep> passedSteps,
            Map<String, Object> details
    ) {
        return new GenerationValidationObservation(targetType, source, passedSteps, details);
    }
}
