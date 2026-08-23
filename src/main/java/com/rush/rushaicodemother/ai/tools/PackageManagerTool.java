package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.ai.tools.policy.DependencyPolicyService;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * package.json 管理工具
 */
@Slf4j
@Component
public class PackageManagerTool extends BaseTool {
    private static final String SCRIPTS = "scripts";
    private static final Set<CodeGenTypeEnum> SUPPORTED_CODE_GENERATION_TYPES = Set.of(
            CodeGenTypeEnum.VUE_PROJECT,
            CodeGenTypeEnum.FULL_STACK_PROJECT
    );

    private final DependencyPolicyService dependencyPolicyService;
    private final ToolExecutionGateway toolExecutionGateway;
    private final ToolWorkspaceFileService workspaceFileService;

    public PackageManagerTool(
            DependencyPolicyService dependencyPolicyService,
            ToolExecutionGateway toolExecutionGateway,
            ToolWorkspaceFileService workspaceFileService
    ) {
        this.dependencyPolicyService = dependencyPolicyService;
        this.toolExecutionGateway = toolExecutionGateway;
        this.workspaceFileService = workspaceFileService;
    }

    /**
 * 返回{@code manage}依赖包{@code Json}。
 *
 * @param action 动作
 * @param packageName 依赖包名称
 * @param version 版本
 * @param dependencyType 依赖类型
 * @param scriptName 待执行脚本名称
 * @param scriptCommand {@code scriptCommand} 对应的调用参数
 * @param runInstall {@code runInstall} 对应的调用参数
 * @param reason 原因
 * @param appId 应用编号
 * @return 处理后的依赖包管理器工具文本
 */
    @Tool("管理 package.json 的依赖和 scripts；依赖安装由后续构建校验流水线统一执行。")
    public String managePackageJson(
            @P("操作类型：getPackageJson、addDependency、updateDependency、removeDependency、setScript、removeScript、installDependencies（仅移交构建阶段）")
            String action,
            @P("依赖名称；依赖操作时必填")
            String packageName,
            @P("依赖版本，如 ^1.0.0；新增或更新依赖时必填")
            String version,
            @P("依赖分组：dependencies 或 devDependencies；为空默认 dependencies")
            String dependencyType,
            @P("脚本名称；脚本操作时必填")
            String scriptName,
            @P("脚本命令；setScript 时必填")
            String scriptCommand,
            @P("是否请求构建校验阶段同步安装依赖和 lock 文件")
            Boolean runInstall,
            @P("依赖新增、更新或删除原因；依赖变更时必填")
            String reason,
            @ToolMemoryId Long appId
    ) {
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            ToolWorkspaceFileService.ToolWorkspaceDirectory projectDirectory =
                    workspaceFileService.resolveDirectory(appId, null);
            ToolWorkspaceFileService.ToolWorkspaceFile packageJsonFile =
                    resolvePackageJsonFile(projectDirectory);
            if (!workspaceFileService.isRegularFile(packageJsonFile)) {
                return "错误：package.json 不存在";
            }
            JSONObject packageJson = JSONUtil.parseObj(workspaceFileService.readUtf8(packageJsonFile));
            String normalizedAction = StrUtil.blankToDefault(action, "getPackageJson");
            return switch (normalizedAction) {
                case "getPackageJson" -> JSONUtil.toJsonPrettyStr(packageJson);
                case "addDependency", "updateDependency" ->
                handleUpsertDependency(appId, packageJsonFile, packageJson, normalizedAction, packageName, version,
                                dependencyType, runInstall, reason);
                case "removeDependency" ->
                        handleRemoveDependency(appId, packageJsonFile, packageJson, packageName, dependencyType, runInstall);
                case "setScript" ->
                        handleSetScript(appId, packageJsonFile, packageJson, scriptName, scriptCommand, runInstall);
                case "removeScript" ->
                        handleRemoveScript(appId, packageJsonFile, packageJson, scriptName, runInstall);
                case "installDependencies" -> deferredInstallMessage();
                default -> "错误：不支持的操作类型 - " + normalizedAction;
            };
        } catch (ToolInputException e) {
            return renderInputError(e);
        } catch (GenerationExecutionPolicyException executionPolicyFailure) {
            // 取消、截止时间与租约丢失属于任务控制信号，不能伪装成可重试的工具反馈。
            throw executionPolicyFailure;
        } catch (Exception e) {
            log.error("管理 package.json 失败，action: {}", action, LogExceptionSanitizer.sanitize(e));
            return "管理 package.json 失败，请稍后重试";
        }
    }

    private ToolWorkspaceFileService.ToolWorkspaceFile resolvePackageJsonFile(
            ToolWorkspaceFileService.ToolWorkspaceDirectory projectDirectory
    ) {
        ToolWorkspaceFileService.ToolWorkspaceFile rootPackage =
                workspaceFileService.resolveFile(projectDirectory, "package.json");
        if (workspaceFileService.isRegularFile(rootPackage)) {
            return rootPackage;
        }
        return workspaceFileService.resolveFile(projectDirectory, "frontend/package.json");
    }

    /** 处理{@code Upsert}依赖。 */
    private String handleUpsertDependency(Long appId,
                                          ToolWorkspaceFileService.ToolWorkspaceFile packageJsonFile,
                                          JSONObject packageJson,
                                          String action,
                                          String packageName,
                                          String version, String dependencyType, Boolean runInstall, String reason) {
        DependencyPolicyService.PolicyDecision decision = dependencyPolicyService.validateAddOrUpdate(
                packageName, version, dependencyType, reason
        );
        if (!decision.allowed()) {
            return "错误：依赖策略拒绝 - " + decision.reason();
        }
        String sectionName = dependencyPolicyService.normalizeDependencyType(dependencyType);
        JSONObject section = packageJson.getJSONObject(sectionName);
        if (section == null) {
            section = new JSONObject();
            packageJson.set(sectionName, section);
        }
        String oldVersion = section.getStr(packageName);
        section.set(packageName, version);
        String writeError = writePackageJson(appId, packageJsonFile, packageJson, action);
        if (writeError != null) {
            return writeError;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(action.equals("addDependency") ? "已添加依赖" : "已更新依赖")
                .append(": ")
                .append(packageName)
                .append(" -> ")
                .append(version)
                .append("\npolicy: ")
                .append(decision.reason())
                .append("\npackage: ")
                .append(packageName)
                .append("\nversion: ")
                .append(version)
                .append("\ndependencyType: ")
                .append(sectionName)
                .append("\nreason: ")
                .append(reason);
        if (StrUtil.isNotBlank(oldVersion)) {
            builder.append("（旧版本: ").append(oldVersion).append("）");
        }
        appendDeferredInstallIfRequested(builder, runInstall);
        return builder.toString();
    }

    /** 处理{@code Remove}依赖。 */
    private String handleRemoveDependency(Long appId,
                                          ToolWorkspaceFileService.ToolWorkspaceFile packageJsonFile,
                                          JSONObject packageJson,
                                          String packageName,
                                          String dependencyType, Boolean runInstall) {
        DependencyPolicyService.PolicyDecision decision = dependencyPolicyService.validateRemove(packageName, dependencyType);
        if (!decision.allowed()) {
            return "错误：依赖策略拒绝 - " + decision.reason();
        }
        String sectionName = dependencyPolicyService.normalizeDependencyType(dependencyType);
        JSONObject section = packageJson.getJSONObject(sectionName);
        if (section == null || !section.containsKey(packageName)) {
            return "提示：在 " + sectionName + " 中未找到依赖 - " + packageName;
        }
        section.remove(packageName);
        String writeError = writePackageJson(appId, packageJsonFile, packageJson, "removeDependency");
        if (writeError != null) {
            return writeError;
        }
        StringBuilder builder = new StringBuilder("已删除依赖: " + packageName + "（" + sectionName + "）");
        appendDeferredInstallIfRequested(builder, runInstall);
        return builder.toString();
    }

    /** 处理集合{@code Script}。 */
    private String handleSetScript(Long appId,
                                   ToolWorkspaceFileService.ToolWorkspaceFile packageJsonFile,
                                   JSONObject packageJson,
                                   String scriptName,
                                   String scriptCommand,
                                   Boolean runInstall) {
        DependencyPolicyService.PolicyDecision decision = dependencyPolicyService.validateScript(scriptName, scriptCommand);
        if (!decision.allowed()) {
            return "错误：依赖策略拒绝 - " + decision.reason();
        }
        JSONObject scripts = packageJson.getJSONObject(SCRIPTS);
        if (scripts == null) {
            scripts = new JSONObject();
            packageJson.set(SCRIPTS, scripts);
        }
        String oldCommand = scripts.getStr(scriptName);
        scripts.set(scriptName, scriptCommand);
        String writeError = writePackageJson(appId, packageJsonFile, packageJson, "setScript");
        if (writeError != null) {
            return writeError;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("已设置脚本: ").append(scriptName).append(" -> ").append(scriptCommand);
        if (StrUtil.isNotBlank(oldCommand)) {
            builder.append("（旧命令: ").append(oldCommand).append("）");
        }
        appendDeferredInstallIfRequested(builder, runInstall);
        return builder.toString();
    }

    /** 处理{@code Remove}{@code Script}。 */
    private String handleRemoveScript(Long appId,
                                      ToolWorkspaceFileService.ToolWorkspaceFile packageJsonFile,
                                      JSONObject packageJson,
                                      String scriptName,
                                      Boolean runInstall) {
        if (StrUtil.isBlank(scriptName)) {
            return "错误：脚本名称不能为空";
        }
        JSONObject scripts = packageJson.getJSONObject(SCRIPTS);
        if (scripts == null || !scripts.containsKey(scriptName)) {
            return "提示：未找到脚本 - " + scriptName;
        }
        scripts.remove(scriptName);
        String writeError = writePackageJson(appId, packageJsonFile, packageJson, "removeScript");
        if (writeError != null) {
            return writeError;
        }
        StringBuilder builder = new StringBuilder("已删除脚本: " + scriptName);
        appendDeferredInstallIfRequested(builder, runInstall);
        return builder.toString();
    }

    private void appendDeferredInstallIfRequested(StringBuilder builder, Boolean runInstall) {
        if (!Boolean.TRUE.equals(runInstall)) {
            return;
        }
        builder.append("\n\n").append(deferredInstallMessage());
    }

    private String deferredInstallMessage() {
        return "依赖安装已移交构建校验流水线，代码生成回合不重复执行 pnpm install";
    }

    /** 写入依赖包{@code Json}。 */
    private String writePackageJson(Long appId,
                                    ToolWorkspaceFileService.ToolWorkspaceFile packageJsonFile,
                                    JSONObject packageJson,
                                    String reason) {
        PatchApplyResult result = toolExecutionGateway.applyPatch(
                appId,
                packageJsonFile.projectRoot(),
                PatchOperation.modify(
                        packageJsonFile.relativePath(), JSONUtil.toJsonPrettyStr(packageJson)),
                "tool-package-json",
                reason
        );
        if ("applied".equals(result.status())) {
            return null;
        }
        return "错误：package.json 写入被拒绝 - " + result.reason();
    }

    @Override
    public ToolRiskLevel getRiskLevel() {
        return ToolRiskLevel.WRITE;
    }

    @Override
    public boolean supportsCodeGeneration(CodeGenTypeEnum codeGenType) {
        return SUPPORTED_CODE_GENERATION_TYPES.contains(codeGenType);
    }

    @Override
    public boolean canMutateWorkspace() {
        return true;
    }

    @Override
    public String getToolName() {
        return "managePackageJson";
    }

    @Override
    public String getDisplayName() {
        return "依赖与脚本管理";
    }

    /**
 * 将工具执行结果整理为模型可消费的文本。
 *
 * @param arguments 参数
 * @return 处理后的方法执行结果文本
 */
    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String action = arguments.getStr("action");
        String packageName = arguments.getStr("packageName");
        String scriptName = arguments.getStr("scriptName");
        String target = StrUtil.isNotBlank(packageName) ? packageName : scriptName;
        return String.format("[工具调用] %s %s %s", getDisplayName(), action, StrUtil.blankToDefault(target, ""));
    }

    /**
 * 将工具执行结果整理为模型可消费的文本。
 *
 * @param arguments 参数
 * @param toolResult 工具结果
 * @return 处理后的方法执行结果文本
 */
    @Override
    public String generateToolExecutedResult(JSONObject arguments, String toolResult) {
        return generateToolExecutedResult(arguments) + "\n" + summarizeResult(toolResult, 280);
    }

    private String summarizeResult(String toolResult, int maxChars) {
        if (StrUtil.isBlank(toolResult)) {
            return "";
        }
        String normalized = toolResult.replace("\r", " ").replace("\n", " ").trim();
        return StrUtil.sub(normalized, 0, Math.min(normalized.length(), maxChars));
    }
}
