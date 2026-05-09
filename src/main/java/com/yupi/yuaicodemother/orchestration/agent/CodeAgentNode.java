package com.yupi.yuaicodemother.orchestration.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.dag.AgentNodeResult;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Code：将结构化 artifact 组装成最终生成规范。
 */
@Component
public class CodeAgentNode extends BaseGenerationAgentNode {

    public CodeAgentNode() {
        super("code", "Code", "codegen", List.of("architect"));
    }

    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        String projectContext = String.valueOf(context.getArtifactValue("context_summary", "projectContext"));
        @SuppressWarnings("unchecked")
        List<String> modules = (List<String>) context.getArtifactValue("architecture_plan", "modules");
        @SuppressWarnings("unchecked")
        List<String> goals = (List<String>) context.getArtifactValue("requirements", "goals");
        String prompt = buildExecutionPrompt(context, projectContext, modules, goals);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enhancedPrompt", prompt);
        payload.put("modulePlan", modules == null ? List.of() : modules);
        payload.put("parallelModuleCount", modules == null ? 0 : modules.size());
        payload.put("executionMode", modules != null && modules.size() > 1 ? "parallel_module_generation" : "single_path_generation");
        GenerationArtifact artifact = GenerationArtifact.of("generation_spec", "Code", "生成规范", payload);
        return AgentNodeResult.of(
                modules != null && modules.size() > 1 ? "已生成模块级执行规范，代码生成器可按模块并行思维执行" : "已生成统一执行规范",
                List.of(artifact),
                Map.of("parallelModuleCount", modules == null ? 0 : modules.size())
        );
    }

    private String buildExecutionPrompt(GenerationAgentContext context,
                                        String projectContext,
                                        List<String> modules,
                                        List<String> goals) {
        List<String> lines = new ArrayList<>();
        lines.add(context.getRequest().userMessage());
        if (context.isUpgradeRequired()) {
            lines.add("");
            lines.add("【模式升级要求】");
            lines.add("当前应用原本使用 " + context.getRequest().currentType().getText() + "，本次需要升级为 "
                    + context.getTargetType().getText() + "。");
            lines.add("必须保留已有业务能力与设计意图，并迁移为可持续迭代的工程结构。");
        }
        if (StrUtil.isNotBlank(projectContext)) {
            lines.add("");
            lines.add(AppConstant.PROJECT_CONTEXT_MARKER);
            lines.add("这是当前项目代码摘要。后续必须基于现有内容继续修改，不能忽略既有实现。");
            lines.add("");
            lines.add(projectContext);
        }
        lines.add("");
        lines.add("【多智能体执行规范】");
        if (goals != null) {
            for (int i = 0; i < goals.size(); i++) {
                lines.add((i + 1) + ". " + goals.get(i));
            }
        }
        lines.add("");
        lines.add("【架构拆分】");
        if (modules != null && !modules.isEmpty()) {
            for (String module : modules) {
                lines.add("- 模块: " + module);
            }
            if (modules.size() > 1) {
                lines.add("- 以上模块需采用并行思维处理，先统一共享约束，再分别产出代码。");
            }
        } else {
            lines.add("- 单模块执行");
        }
        lines.add("");
        lines.add("【质量要求】");
        lines.add("1. 复用现有依赖、目录、页面与组件边界。");
        lines.add("2. 只做满足需求所需的最小闭环改动。");
        lines.add("3. 代码输出后要能通过 Review 与 BuildFix 阶段。");
        return String.join("\n", lines);
    }
}
