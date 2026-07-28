package com.rush.rushaicodemother.orchestration.agent;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationNodeReplayPolicy;
import com.rush.rushaicodemother.service.GenerationContextCompressionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Code：将结构化 artifact 组装成最终生成规范。
 */
@Component
public class CodeAgentNode extends BaseGenerationAgentNode {

    private final GenerationContextCompressionService contextCompressionService;

    public CodeAgentNode(GenerationContextCompressionService contextCompressionService) {
        super("code", "Code", "codegen", List.of("architect"), GenerationNodeReplayPolicy.REPLAY_SAFE);
        this.contextCompressionService = Objects.requireNonNull(
                contextCompressionService,
                "contextCompressionService must not be null"
        );
    }

    /**
 * 执行代码智能体节点处理流程。
 *
 * @param context 执行上下文
 * @return 代码智能体节点
 */
    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        String projectContext = artifactStringValue(context, "context_summary", "projectContext", "");
        @SuppressWarnings("unchecked")
        List<String> modules = (List<String>) context.getArtifactValue("architecture_plan", "modules");
        @SuppressWarnings("unchecked")
        List<String> goals = (List<String>) context.getArtifactValue("requirements", "goals");
        @SuppressWarnings("unchecked")
        List<String> selectedFiles = (List<String>) context.getArtifactValue("context_summary", "selectedFiles");
        List<Map<String, Object>> recipes = readRecipePayloads(context);
        List<Map<String, Object>> skills = readSkillPayloads(context);
        String templateId = artifactStringValue(context, "template_bootstrap", "templateId", "");
        boolean templateBootstrapped = artifactBooleanValue(context, "template_bootstrap", "bootstrapped");
        boolean patchFirst = artifactBooleanValue(context, "requirements", "patchFirst");
        boolean requiresBuild = artifactBooleanValue(context, "requirements", "requiresBuild");
        String validationMode = artifactStringValue(context, "requirements", "validationMode",
                requiresBuild ? "build_validation" : "review_only");
        String generationMode = artifactStringValue(context, "requirements", "generationMode",
                patchFirst ? "patch_first_update" : "full_generation");
        String prompt = buildExecutionPrompt(context, projectContext, modules, goals, skills, recipes);
        ChangePlan changePlan = buildChangePlan(modules, selectedFiles, patchFirst, validationMode, requiresBuild);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enhancedPrompt", prompt);
        payload.put("modulePlan", modules == null ? List.of() : modules);
        payload.put("parallelModuleCount", modules == null ? 0 : modules.size());
        payload.put("executionMode", modules != null && modules.size() > 1 ? "parallel_module_generation" : "single_path_generation");
        payload.put("patchFirst", patchFirst);
        payload.put("requiresBuild", requiresBuild);
        payload.put("validationMode", validationMode);
        payload.put("generationMode", generationMode);
        payload.put("templateId", templateId);
        payload.put("templateBootstrapped", templateBootstrapped);
        payload.put("artifactMode", patchFirst ? "patch_plan" : "generation_plan");
        payload.put("changePlan", changePlan.toPayload());
        payload.put("skillIds", readSkillIds(skills));
        payload.put("skills", skills);
        payload.put("recipes", recipes);
        GenerationArtifact changePlanArtifact = GenerationArtifact.of("change_plan", "Code", "变更计划", changePlan.toPayload());
        GenerationArtifact artifact = GenerationArtifact.of("generation_spec", "Code", "生成规范", payload);
        return AgentNodeResult.of(
                modules != null && modules.size() > 1 ? "已生成模块级执行规范，代码生成器可按模块并行思维执行" : "已生成统一执行规范",
                List.of(changePlanArtifact, artifact),
                Map.of(
                        "parallelModuleCount", modules == null ? 0 : modules.size(),
                        "patchFirst", patchFirst,
                        "requiresBuild", requiresBuild,
                        "validationMode", validationMode,
                        "generationMode", generationMode,
                        "changeScope", changePlan.changeScope()
                )
        );
    }

    /** 构建并返回执行提示词。 */
    private String buildExecutionPrompt(GenerationAgentContext context,
                                        String projectContext,
                                        List<String> modules,
                                        List<String> goals,
                                        List<Map<String, Object>> skills,
                                        List<Map<String, Object>> recipes) {
        boolean patchFirst = artifactBooleanValue(context, "requirements", "patchFirst");
        boolean requiresBuild = artifactBooleanValue(context, "requirements", "requiresBuild");
        String validationMode = artifactStringValue(context, "requirements", "validationMode",
                requiresBuild ? "build_validation" : "review_only");
        String generationMode = artifactStringValue(context, "requirements", "generationMode",
                patchFirst ? "patch_first_update" : "full_generation");
        List<String> lines = new ArrayList<>();
        lines.add(context.getRequest().userMessage());
        if (context.isUpgradeRequired()) {
            lines.add("");
            lines.add("【模式升级要求】");
            lines.add("当前应用原本使用 " + context.getRequest().currentType().getText() + "，本次需要升级为 "
                    + context.getTargetType().getText() + "。");
            lines.add("必须保留已有业务能力与设计意图，并迁移为可持续迭代的工程结构。");
        }
        String memoryContext = artifactStringValue(context, "context_summary", "memoryContext", "");
        if (StrUtil.isNotBlank(memoryContext)) {
            lines.add("");
            lines.add(memoryContext);
        }
        if (StrUtil.isNotBlank(projectContext)) {
            lines.add("");
            lines.add(AppConstant.PROJECT_CONTEXT_MARKER);
            lines.add("基于当前项目继续修改，不要重建无关内容。");
            lines.add("");
            lines.add(projectContext);
        }
        String templateId = artifactStringValue(context, "template_bootstrap", "templateId", "");
        if (context.getTargetType() == com.rush.rushaicodemother.model.enums.CodeGenTypeEnum.FULL_STACK_PROJECT) {
            appendFullStackContext(lines, context);
        }
        appendApiContractContext(lines, context);
        if (StrUtil.isNotBlank(templateId)) {
            lines.add("");
            if (context.getTargetType() == com.rush.rushaicodemother.model.enums.CodeGenTypeEnum.FULL_STACK_PROJECT) {
                lines.add("【模板基线】当前全栈工程已基于模板 " + templateId + " 初始化，前端位于 frontend/，Go + SQLite 后端位于 backend/。");
                lines.add("前端只通过 import.meta.env.VITE_API_BASE_URL 访问后端；后端监听 SERVER_ADDR，容器化仅预留 Dockerfile/docker-compose/.env.example，不自动运行服务。");
                appendBackendTemplateStrategy(lines, "backend/");
            } else if (context.getTargetType() == com.rush.rushaicodemother.model.enums.CodeGenTypeEnum.BACKEND_PROJECT) {
                lines.add("【模板基线】当前 Go + SQLite 后端工程已基于模板 " + templateId + " 初始化。");
                lines.add("优先改造 cmd/server、internal/domain、internal/modules/sample 和 sql/schema.sql，保留 config/database/middleware/response/validator 等稳定基础设施。");
                appendBackendTemplateStrategy(lines, "");
            } else {
                lines.add("【模板基线】当前 Vue 工程已基于模板 " + templateId + " 初始化。");
                lines.add("优先改造模板内的 src/data、src/pages、src/views、src/components 和 src/styles，保留 package.json、vite.config.js、index.html、src/main.js、src/router/index.js 等稳定工程入口。");
            }
        }
        lines.add("");
        lines.add("【执行规范】mode=" + generationMode + ", patchFirst=" + patchFirst
                + ", validation=" + validationMode + ", build=" + requiresBuild);
        if (patchFirst) {
            lines.add("patchFirst: 先给计划型 artifact，再输出最小 patch；只改必要文件。");
            lines.add("patchFirst: 必须遵守 changePlan 的文件边界与回滚策略。");
        }
        lines.add("【架构】modules=" + formatModulesForPrompt(modules));
        appendSkillInstructions(lines, skills);
        appendRecipeInstructions(lines, recipes);
        lines.add("【ChangePlan】" + "scope=" + buildChangeScope(modules, patchFirst)
                + ", validate=" + validationMode
                + ", rollback=" + (requiresBuild ? "snapshot_or_manual_retry" : "manual_retry")
                + ", modify=" + formatFileListForPrompt(context.getArtifactValue("context_summary", "selectedFiles"))
                + ", add=[]"
                + ", delete=[]"
                + ", impacted=" + formatModulesForPrompt(modules));
        lines.add("【质量】最小闭环，复用现有结构，避免无关重构。");
        lines.add(requiresBuild
                ? "【门禁】需要 Review + BuildFix。"
                : "【门禁】仅 Review，默认跳过 BuildFix。");
        if (goals != null && !goals.isEmpty()) {
            for (int i = 0; i < goals.size(); i++) {
                lines.add((i + 1) + ". " + goals.get(i));
            }
        }
        return contextCompressionService.compressFinalPrompt(String.join("\n", lines));
    }

    /** 追加全栈上下文。 */
    private void appendFullStackContext(List<String> lines, GenerationAgentContext context) {
        lines.add("");
        lines.add("【全栈共享上下文】");
        lines.add("workspaceRoot=" + artifactStringValue(context, "full_stack_context", "workspaceRoot", ""));
        lines.add("frontendPath=" + artifactStringValue(context, "full_stack_context", "frontendPath", "frontend"));
        lines.add("backendPath=" + artifactStringValue(context, "full_stack_context", "backendPath", "backend"));
        lines.add("backendBaseUrl=" + artifactStringValue(context, "full_stack_context", "backendBaseUrl", ""));
        lines.add("apiPrefix=" + artifactStringValue(context, "full_stack_context", "apiPrefix", "/api"));
        lines.add("frontendEnv=" + artifactStringValue(context, "full_stack_context", "frontendApiEnvName", "VITE_API_BASE_URL")
                + "=" + artifactStringValue(context, "full_stack_context", "frontendApiEnvValue", ""));
        lines.add("backendServerAddr=" + artifactStringValue(context, "full_stack_context", "backendServerAddr", ""));
        lines.add("要求：前端文件路径必须以 frontend/ 开头，后端文件路径必须以 backend/ 开头。");
        lines.add("要求：前端 API baseURL 只能读取 VITE_API_BASE_URL；后端端口只能读取 SERVER_ADDR。");
    }

    private void appendApiContractContext(List<String> lines, GenerationAgentContext context) {
        Object contract = context.getArtifactValue("api_contract", "contract");
        if (!(contract instanceof Map<?, ?> contractMap) || contractMap.isEmpty()) {
            return;
        }
        lines.add("");
        lines.add("【API 字段契约】");
        lines.add("契约来源=" + artifactStringValue(context, "api_contract", "source", "planner"));
        lines.add("要求：前端表单/列表字段、后端 DTO/VO、Repository scan 字段、SQLite schema 字段必须以该契约为准；若需求变化，先同步更新契约再改代码。");
        lines.add(contractMap.toString());
    }

    private void appendBackendTemplateStrategy(List<String> lines, String pathPrefix) {
        lines.add("【后端模板化策略】");
        lines.add("- 先定义 API 契约和数据模型，再同步修改 " + pathPrefix + "internal/domain、" + pathPrefix + "internal/modules/sample/{model,repository,service,handler}.go、" + pathPrefix + "sql/schema.sql。");
        lines.add("- Repository 必须使用 SQLite 参数化 SQL；Handler 必须使用 internal/response 统一 JSON 响应；Service 负责业务规则和中文错误消息。");
        lines.add("- 不新增 Go Web 框架或 ORM，不改稳定基础设施文件，不硬编码密钥、私有地址、端口或数据库路径。");
        lines.add("- 新增业务能力优先沿用模板中的 @AI_INJECT_MODULE_WIRING、@AI_INJECT_ROUTE 锚点和 RegisterRoutes 约定，避免重建工程骨架。");
    }

    /** 读取{@code Recipe}{@code Payloads}。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readRecipePayloads(GenerationAgentContext context) {
        Object contextRecipes = context.getArtifactValue("context_summary", "recipes");
        if (contextRecipes instanceof List<?> list && list.stream().allMatch(Map.class::isInstance)) {
            return (List<Map<String, Object>>) contextRecipes;
        }
        Object requirementRecipes = context.getArtifactValue("requirements", "recipes");
        if (requirementRecipes instanceof List<?> list && list.stream().allMatch(Map.class::isInstance)) {
            return (List<Map<String, Object>>) requirementRecipes;
        }
        return List.of();
    }

    /** 读取{@code Skill}{@code Payloads}。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readSkillPayloads(GenerationAgentContext context) {
        Object contextSkills = context.getArtifactValue("context_summary", "skills");
        if (contextSkills instanceof List<?> list && list.stream().allMatch(Map.class::isInstance)) {
            return (List<Map<String, Object>>) contextSkills;
        }
        Object requirementSkills = context.getArtifactValue("requirements", "skills");
        if (requirementSkills instanceof List<?> list && list.stream().allMatch(Map.class::isInstance)) {
            return (List<Map<String, Object>>) requirementSkills;
        }
        return List.of();
    }

    /** 追加{@code Recipe}{@code Instructions}。 */
    @SuppressWarnings("unchecked")
    private void appendRecipeInstructions(List<String> lines, List<Map<String, Object>> recipes) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (recipes == null || recipes.isEmpty()) {
            return;
        }
        lines.add("【Recipe】matched=" + recipes.stream()
                .map(recipe -> String.valueOf(recipe.get("id")))
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList());
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (Map<String, Object> recipe : recipes) {
            lines.add("- " + recipe.get("title") + ": modules=" + recipe.get("modules"));
            Object steps = recipe.get("implementationSteps");
            if (steps instanceof List<?> stepList && !stepList.isEmpty()) {
                lines.add("  steps=" + stepList.stream().limit(3).toList());
            }
            Object validationHints = recipe.get("validationHints");
            if (validationHints instanceof List<?> hintList && !hintList.isEmpty()) {
                lines.add("  validation=" + hintList.stream().limit(2).toList());
            }
            Object templateFiles = recipe.get("templateFiles");
            if (templateFiles instanceof List<?> fileList && !fileList.isEmpty()) {
                lines.add("  templateFiles=" + fileList.stream().limit(6).toList());
            }
            Object aiFillSlots = recipe.get("aiFillSlots");
            if (aiFillSlots instanceof List<?> slotList && !slotList.isEmpty()) {
                lines.add("  aiFillSlots=" + slotList.stream().limit(8).toList());
                lines.add("  要求：优先落地上述模板文件/片段，AI 只补齐这些 slots 和少量业务差异，不要把 recipe 当作普通 prompt 重写。");
            }
            if (Boolean.TRUE.equals(recipe.get("databaseRequired"))) {
                lines.add("  database=true，必须遵守 Database 服务接入边界。");
            }
        }
    }

    /** 追加{@code Skill}{@code Instructions}。 */
    private void appendSkillInstructions(List<String> lines, List<Map<String, Object>> skills) {
        if (skills == null || skills.isEmpty()) {
            return;
        }
        lines.add("【Skill】matched=" + readSkillIds(skills));
        for (Map<String, Object> skill : skills) {
            lines.add("- " + skill.get("title") + ": modules=" + skill.get("modules"));
            Object implementationHints = skill.get("implementationHints");
            if (implementationHints instanceof List<?> hintList && !hintList.isEmpty()) {
                lines.add("  implementation=" + hintList.stream().limit(3).toList());
            }
            Object validationHints = skill.get("validationHints");
            if (validationHints instanceof List<?> hintList && !hintList.isEmpty()) {
                lines.add("  validation=" + hintList.stream().limit(3).toList());
            }
            if (Boolean.TRUE.equals(skill.get("databaseRequired"))) {
                lines.add("  database=true，必须遵守 Database 服务接入边界。");
            }
            Object instructions = skill.get("promptInstructions");
            if (instructions instanceof String instructionText && StrUtil.isNotBlank(instructionText)) {
                lines.add("  instructions:");
                for (String line : instructionText.split("\\R")) {
                    if (StrUtil.isNotBlank(line)) {
                        lines.add("    " + line);
                    }
                }
            }
        }
    }

    /** 构建并返回{@code Change}计划。 */
    private ChangePlan buildChangePlan(List<String> modules,
                                       List<String> selectedFiles,
                                       boolean patchFirst,
                                       String validationMode,
                                       boolean requiresBuild) {
        List<String> normalizedModules = modules == null ? List.of() : modules.stream()
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        List<String> normalizedSelectedFiles = normalizeSelectedFiles(selectedFiles);
        return new ChangePlan(
                "v1",
                buildChangeScope(normalizedModules, patchFirst),
                List.of(),
                patchFirst ? normalizedSelectedFiles : List.of(),
                List.of(),
                normalizedModules,
                validationMode,
                requiresBuild ? "rollback_to_last_stable_snapshot_or_manual_retry" : "manual_retry_without_snapshot"
        );
    }

    /** 构建并返回{@code Change}作用域。 */
    private String buildChangeScope(List<String> modules, boolean patchFirst) {
        if (!patchFirst) {
            return "project_bootstrap";
        }
        if (modules != null && modules.size() > 1) {
            return "cross_module_patch";
        }
        return "single_module_patch";
    }

    @SuppressWarnings("unchecked")
    private String formatFileListForPrompt(Object selectedFilesObj) {
        if (!(selectedFilesObj instanceof List<?> selectedFiles)) {
            return "[]";
        }
        List<String> normalizedSelectedFiles = normalizeSelectedFiles((List<String>) selectedFiles);
        return normalizedSelectedFiles.isEmpty() ? "[]" : normalizedSelectedFiles.toString();
    }

    private String formatModulesForPrompt(List<String> modules) {
        if (modules == null || modules.isEmpty()) {
            return "[core-app]";
        }
        return modules.stream().filter(StrUtil::isNotBlank).distinct().toList().toString();
    }

    private List<String> readSkillIds(List<Map<String, Object>> skills) {
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        return skills.stream()
                .map(skill -> String.valueOf(skill.get("id")))
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
    }

    private List<String> normalizeSelectedFiles(List<String> selectedFiles) {
        return ChangePlan.normalizeFilePaths(selectedFiles);
    }

}
