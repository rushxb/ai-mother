package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineCapabilityRegistry;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 在产生积分或持久化副作用前验证冻结场景具备实际 pipeline 能力。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GenerationTaskCapabilityAdmissionPolicy implements GenerationTaskAdmissionPolicy {

    private final GenerationPipelineCapabilityRegistry capabilityRegistry;

    public GenerationTaskCapabilityAdmissionPolicy(
            GenerationPipelineCapabilityRegistry capabilityRegistry
    ) {
        this.capabilityRegistry = Objects.requireNonNull(
                capabilityRegistry, "生成管线能力注册表不能为空");
    }

    @Override
    public void assertMayAdmit(GenerationTaskAdmissionContext context) {
        Objects.requireNonNull(context, "生成任务准入上下文不能为空");
        capabilityRegistry.requireCapability(context.command().scenarioDecision());
    }
}
