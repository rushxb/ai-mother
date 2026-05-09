package com.yupi.yuaicodemother.orchestration.agent;

import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.artifact.QualityGateResult;
import com.yupi.yuaicodemother.orchestration.dag.AgentNodeResult;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Review：生成前质量门禁。
 */
@Component
public class ReviewAgentNode extends BaseGenerationAgentNode {

    public ReviewAgentNode() {
        super("review", "Review", "quality", List.of("code"));
    }

    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> passes = new ArrayList<>();
        Object promptObj = context.getArtifactValue("generation_spec", "enhancedPrompt");
        String prompt = promptObj == null ? "" : String.valueOf(promptObj);
        if (prompt.isBlank()) {
            blockers.add("生成规范为空，无法进入代码生成");
        } else {
            passes.add("生成规范已构建");
        }
        if (context.getTargetType() == CodeGenTypeEnum.VUE_PROJECT) {
            passes.add("目标模式为工程化项目，将启用 BuildFix 门禁");
        } else {
            warnings.add("当前目标不是工程模式，构建校验能力受限");
        }
        QualityGateResult gateResult = blockers.isEmpty()
                ? QualityGateResult.passed(warnings, passes)
                : QualityGateResult.failed(blockers, warnings, passes);
        context.setQualityGateResult(gateResult);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("passed", gateResult.passed());
        payload.put("level", gateResult.level());
        payload.put("blockers", gateResult.blockers());
        payload.put("warnings", gateResult.warnings());
        payload.put("passes", gateResult.passes());
        GenerationArtifact artifact = GenerationArtifact.of("quality_gate", "Review", "质量门禁", payload);
        return AgentNodeResult.of(
                gateResult.passed() ? "质量门禁通过，允许执行代码生成" : "质量门禁未通过，阻止后续生成",
                List.of(artifact),
                payload
        );
    }
}
