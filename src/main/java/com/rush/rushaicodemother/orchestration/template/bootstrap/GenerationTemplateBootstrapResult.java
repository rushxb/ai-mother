package com.rush.rushaicodemother.orchestration.template.bootstrap;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 调用方共享的模板初始化结果及其两种稳定投影。 */
public record GenerationTemplateBootstrapResult(
        CodeGenTypeEnum codeGenType,
        boolean supported,
        boolean successful,
        GenerationWorkspace workspace,
        String artifactName,
        String summary,
        Map<String, Object> templatePayload,
        Map<String, Map<String, Object>> rolePayloads,
        Map<String, Object> contextPayload
) {

    public GenerationTemplateBootstrapResult {
        templatePayload = templatePayload == null ? Map.of() : Map.copyOf(templatePayload);
        rolePayloads = rolePayloads == null ? Map.of() : Map.copyOf(rolePayloads);
        contextPayload = contextPayload == null ? Map.of() : Map.copyOf(contextPayload);
        if (supported && (codeGenType == null || workspace == null
                || artifactName == null || artifactName.isBlank()
                || summary == null || summary.isBlank())) {
            throw new IllegalArgumentException("受支持的模板初始化结果缺少必要事实");
        }
    }

    /** 返回 patch 与构建共同使用的规范项目根目录。 */
    public Path projectRoot() {
        return workspace == null ? null : workspace.canonicalRootPath();
    }

    /** 返回 CREATE runtime 使用的角色化模板与运行上下文。 */
    public Map<String, Object> runtimePayload() {
        if (!supported || (rolePayloads.isEmpty() && contextPayload.isEmpty())) {
            return templatePayload;
        }
        Map<String, Object> payload = new LinkedHashMap<>(contextPayload);
        rolePayloads.forEach(payload::put);
        return Map.copyOf(payload);
    }

    static GenerationTemplateBootstrapResult completed(
            CodeGenTypeEnum codeGenType,
            GenerationWorkspace workspace,
            GenerationTemplateBootstrapOutput output
    ) {
        return new GenerationTemplateBootstrapResult(
                codeGenType,
                true,
                output.successful(),
                workspace,
                output.artifactName(),
                output.summary(),
                output.templatePayload(),
                output.rolePayloads(),
                output.contextPayload()
        );
    }

    static GenerationTemplateBootstrapResult unsupported(
            CodeGenTypeEnum codeGenType,
            String reason
    ) {
        String targetType = codeGenType == null ? "" : codeGenType.getValue();
        Map<String, Object> payload = Map.of(
                "bootstrapped", false,
                "templateId", "",
                "projectPath", "",
                "fileCount", 0,
                "targetType", targetType,
                "reason", reason
        );
        return new GenerationTemplateBootstrapResult(
                codeGenType,
                false,
                false,
                null,
                "项目模板",
                "无需复制项目模板",
                payload,
                Map.of(),
                Map.of()
        );
    }
}
