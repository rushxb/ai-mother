package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * 统一解析 Agent 工具回合、最终模型响应槽位与类型下限。
 *
 * <p>计划任务携带模型 profile，旧检查点可能没有 profile。两种输入通过同一 interface
 * 恢复预算，避免 Selector、自动修复和审批恢复分别维护工程类型数字。</p>
 */
@Component
public final class GenerationAgentBudgetPolicy {

    private static final int DEFAULT_LEGACY_TOOL_ROUNDS = 10;

    private static final Map<CodeGenTypeEnum, Integer> PLANNED_TOOL_ROUND_FLOORS = Map.of(
            CodeGenTypeEnum.BACKEND_PROJECT, 15,
            CodeGenTypeEnum.FULL_STACK_PROJECT, 20
    );

    private static final Map<CodeGenTypeEnum, Integer> LEGACY_TOOL_ROUND_LIMITS = Map.of(
            CodeGenTypeEnum.BACKEND_PROJECT, 20,
            CodeGenTypeEnum.FULL_STACK_PROJECT, 32
    );

    /** 根据冻结 profile 或旧任务兼容规则返回完整 Agent 回合预算。 */
    public GenerationAgentBudget resolve(
            CodeGenTypeEnum codeGenType,
            GenerationPerformanceProfile profile
    ) {
        Objects.requireNonNull(codeGenType, "代码生成类型不能为空");
        if (profile == null) {
            int toolRoundLimit = LEGACY_TOOL_ROUND_LIMITS.getOrDefault(
                    codeGenType,
                    DEFAULT_LEGACY_TOOL_ROUNDS
            );
            return GenerationAgentBudget.legacy(toolRoundLimit);
        }

        int configuredLimit = requireValidLimit(profile.maxToolInvocations());
        int projectFloor = PLANNED_TOOL_ROUND_FLOORS.getOrDefault(codeGenType, 1);
        int toolRoundLimit = Math.max(configuredLimit, projectFloor);
        GenerationPerformanceProfile effectiveProfile = toolRoundLimit == configuredLimit
                ? profile
                : withToolRoundLimit(profile, toolRoundLimit);
        return GenerationAgentBudget.planned(effectiveProfile, toolRoundLimit);
    }

    private GenerationPerformanceProfile withToolRoundLimit(
            GenerationPerformanceProfile profile,
            int toolRoundLimit
    ) {
        String reasoning = profile.reasoning() == null || profile.reasoning().isBlank()
                ? "按工程类型保护 Agent 工具回合下限"
                : profile.reasoning() + "（已按工程类型调整工具上限）";
        return new GenerationPerformanceProfile(
                profile.modelTier(),
                profile.thinkingEnabled(),
                toolRoundLimit,
                reasoning
        );
    }

    private int requireValidLimit(int toolRoundLimit) {
        if (toolRoundLimit <= 0 || toolRoundLimit == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("智能体工具回合上限无效");
        }
        return toolRoundLimit;
    }

    /** 一次解析即可供计划、运行时与工具协议共同消费的不可变预算。 */
    public record GenerationAgentBudget(
            GenerationPerformanceProfile effectiveProfile,
            int toolRoundLimit,
            int maximumModelResponses,
            BudgetSource source
    ) {

        private static GenerationAgentBudget planned(
                GenerationPerformanceProfile profile,
                int toolRoundLimit
        ) {
            return new GenerationAgentBudget(
                    Objects.requireNonNull(profile, "计划任务性能配置不能为空"),
                    toolRoundLimit,
                    Math.addExact(toolRoundLimit, 1),
                    BudgetSource.PLANNED_PROFILE
            );
        }

        private static GenerationAgentBudget legacy(int toolRoundLimit) {
            return new GenerationAgentBudget(
                    null,
                    toolRoundLimit,
                    Math.addExact(toolRoundLimit, 1),
                    BudgetSource.LEGACY_COMPATIBILITY
            );
        }

        public GenerationAgentBudget {
            Objects.requireNonNull(source, "Agent 预算来源不能为空");
            if (toolRoundLimit <= 0
                    || maximumModelResponses != Math.addExact(toolRoundLimit, 1)) {
                throw new IllegalArgumentException("Agent 回合预算无效");
            }
            if ((source == BudgetSource.PLANNED_PROFILE) != (effectiveProfile != null)) {
                throw new IllegalArgumentException("Agent 预算来源与性能配置不一致");
            }
        }
    }

    public enum BudgetSource {
        PLANNED_PROFILE,
        LEGACY_COMPATIBILITY
    }
}
