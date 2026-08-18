package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 生成性能配置选择器。
 * <p>
 * 根据生成上下文（是否首次、复杂度、类型）选择最优的性能配置，
 * 在生成质量和响应速度之间取得平衡。
 */
@Slf4j
@Component
public class GenerationPerformanceSelector {

    private final GenerationAgentBudgetPolicy agentBudgetPolicy;

    @Autowired
    public GenerationPerformanceSelector(GenerationAgentBudgetPolicy agentBudgetPolicy) {
        this.agentBudgetPolicy = Objects.requireNonNull(
                agentBudgetPolicy,
                "Agent 回合预算策略不能为空"
        );
    }

    /**
     * 选择性能配置。
     *
     * @param isFirstGeneration 是否首次生成（无已有代码）
     * @param isComplex         是否复杂请求
     * @param codeGenType       代码生成类型
     * @return 性能配置
     */
    public GenerationPerformanceProfile select(boolean isFirstGeneration,
                                                boolean isComplex,
                                                CodeGenTypeEnum codeGenType) {
        Objects.requireNonNull(codeGenType, "代码生成类型不能为空");
        GenerationPerformanceProfile profile;

        if (isFirstGeneration && !isComplex) {
            // 首次简单生成 → 极速模式
            profile = GenerationPerformanceProfile.speedFirst();
        } else if (isFirstGeneration && isComplex
                && (codeGenType == CodeGenTypeEnum.BACKEND_PROJECT
                || codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT)) {
            profile = GenerationPerformanceProfile.qualityFirst();
        } else if (isFirstGeneration && isComplex) {
            // 首次复杂生成 → 平衡模式（不用 thinking，靠 tool 调用保证质量）
            profile = GenerationPerformanceProfile.balanced();
        } else if (!isFirstGeneration && isComplex) {
            // 改修复杂场景 → 质量优先
            profile = GenerationPerformanceProfile.qualityFirst();
        } else {
            // 改修简单场景 → 平衡模式
            profile = GenerationPerformanceProfile.balanced();
        }

        // profile 选择与工程工具预算是不同事实，由统一预算 module 负责合并。
        profile = agentBudgetPolicy.resolve(codeGenType, profile).effectiveProfile();

        log.info("选择生成性能配置: tier={}, thinking={}, maxTools={}, type={}, first={}, complex={}, reason={}",
                profile.modelTier(), profile.thinkingEnabled(), profile.maxToolInvocations(),
                codeGenType, isFirstGeneration, isComplex, profile.reasoning());

        return profile;
    }
}
