package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.ai.tools.policy.DependencyPolicyService;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.governance.app.AppGenerationControlPolicy;
import com.rush.rushaicodemother.orchestration.governance.app.AppGenerationControlReader;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
import com.rush.rushaicodemother.orchestration.tool.ToolResultEvidence;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.data.message.TextContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
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
    private final AppGenerationControlReader appControlReader;

    @Autowired
    public PackageManagerTool(
            DependencyPolicyService dependencyPolicyService,
            ToolExecutionGateway toolExecutionGateway,
            ToolWorkspaceFileService workspaceFileService,
            AppGenerationControlReader appControlReader
    ) {
        this.dependencyPolicyService = dependencyPolicyService;
        this.toolExecutionGateway = toolExecutionGateway;
        this.workspaceFileService = workspaceFileService;
        this.appControlReader = appControlReader;
    }

    /** 兼容不接入应用控制的工具单元测试。 */
    public PackageManagerTool(
            DependencyPolicyService dependencyPolicyService,
            ToolExecutionGateway toolExecutionGateway,
            ToolWorkspaceFileService workspaceFileService
    ) {
        this(dependencyPolicyService, toolExecutionGateway, workspaceFileService,
                AppGenerationControlReader.defaultsOnly());
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
    public TextContent managePackageJson(
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
                throw toolFailure("错误：package.json 不存在");
            }
            JSONObject packageJson = JSONUtil.parseObj(workspaceFileService.readUtf8(packageJsonFile));
            String normalizedAction = StrUtil.blankToDefault(action, "getPackageJson");
            assertApplicationAllowsMutation(appId, normalizedAction);
            PackageOperationResult operationResult = switch (normalizedAction) {
                case "getPackageJson" -> PackageOperationResult.queryResult(
                        JSONUtil.toJsonPrettyStr(packageJson));
                case "addDependency", "updateDependency" ->
                handleUpsertDependency(appId, packageJsonFile, packageJson, normalizedAction, packageName, version,
                                dependencyType, runInstall, reason);
                case "removeDependency" ->
                        handleRemoveDependency(appId, packageJsonFile, packageJson, packageName, dependencyType, runInstall);
                case "setScript" ->
                        handleSetScript(appId, packageJsonFile, packageJson, scriptName, scriptCommand, runInstall);
                case "removeScript" ->
                        handleRemoveScript(appId, packageJsonFile, packageJson, scriptName, runInstall);
                case "installDependencies" ->
                        PackageOperationResult.queryResult(deferredInstallMessage());
                default -> throw toolFailure("错误：不支持的操作类型 - " + normalizedAction);
            };
            return operationResult.mutationAttempt()
                    ? ToolResultEvidence.effectiveMutations(
                            operationResult.displayResult(),
                            operationResult.effectivePaths())
                    : TextContent.from(operationResult.displayResult());
        } catch (ToolPublicFailureException publicFailure) {
            throw publicFailure;
        } catch (ToolInputException e) {
            throw toolInputFailure("错误：", e);
        } catch (GenerationExecutionPolicyException executionPolicyFailure) {
            // 取消、截止时间与租约丢失属于任务控制信号，不能伪装成可重试的工具反馈。
            throw executionPolicyFailure;
        } catch (Exception e) {
            log.error("管理 package.json 失败，action: {}", action, LogExceptionSanitizer.sanitize(e));
            throw toolFailure("管理 package.json 失败，请稍后重试");
        }
    }

    private void assertApplicationAllowsMutation(Long appId, String action) {
        if (!Set.of("addDependency", "updateDependency", "removeDependency",
                "setScript", "removeScript").contains(action)) {
            return;
        }
        AppGenerationControlPolicy policy = appControlReader.get(appId);
        if (policy.emergencyStopped()) {
            throw new GenerationExecutionPolicyException("应用已紧急停止生成");
        }
        if (policy.dependencyMutationPolicy()
                == AppGenerationControlPolicy.DependencyMutationPolicy.DENY) {
            throw toolFailure("错误：应用策略禁止修改依赖或脚本");
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
    private PackageOperationResult handleUpsertDependency(Long appId,
                                          ToolWorkspaceFileService.ToolWorkspaceFile packageJsonFile,
                                          JSONObject packageJson,
                                          String action,
                                          String packageName,
                                          String version, String dependencyType, Boolean runInstall, String reason) {
        DependencyPolicyService.PolicyDecision decision = dependencyPolicyService.validateAddOrUpdate(
                packageName, version, dependencyType, reason
        );
        if (!decision.allowed()) {
            throw toolFailure("错误：依赖策略拒绝 - " + decision.reason());
        }
        String sectionName = dependencyPolicyService.normalizeDependencyType(dependencyType);
        JSONObject section = packageJson.getJSONObject(sectionName);
        if (section == null) {
            section = new JSONObject();
            packageJson.set(sectionName, section);
        }
        String oldVersion = section.getStr(packageName);
        section.set(packageName, version);
        List<String> effectivePaths = writePackageJson(
                appId, packageJsonFile, packageJson, action);
        StringBuilder builder = new StringBuilder();
        builder.append(effectivePaths.isEmpty()
                        ? "依赖已是目标状态，无需重复修改"
                        : action.equals("addDependency") ? "已添加依赖" : "已更新依赖")
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
        return new PackageOperationResult(builder.toString(), effectivePaths);
    }

    /** 处理{@code Remove}依赖。 */
    private PackageOperationResult handleRemoveDependency(Long appId,
                                          ToolWorkspaceFileService.ToolWorkspaceFile packageJsonFile,
                                          JSONObject packageJson,
                                          String packageName,
                                          String dependencyType, Boolean runInstall) {
        DependencyPolicyService.PolicyDecision decision = dependencyPolicyService.validateRemove(packageName, dependencyType);
        if (!decision.allowed()) {
            throw toolFailure("错误：依赖策略拒绝 - " + decision.reason());
        }
        String sectionName = dependencyPolicyService.normalizeDependencyType(dependencyType);
        JSONObject section = packageJson.getJSONObject(sectionName);
        if (section == null || !section.containsKey(packageName)) {
            return PackageOperationResult.confirmedNoMutation(
                    "提示：在 " + sectionName + " 中未找到依赖 - " + packageName);
        }
        section.remove(packageName);
        List<String> effectivePaths = writePackageJson(
                appId, packageJsonFile, packageJson, "removeDependency");
        StringBuilder builder = new StringBuilder("已删除依赖: " + packageName + "（" + sectionName + "）");
        appendDeferredInstallIfRequested(builder, runInstall);
        return new PackageOperationResult(builder.toString(), effectivePaths);
    }

    /** 处理集合{@code Script}。 */
    private PackageOperationResult handleSetScript(Long appId,
                                   ToolWorkspaceFileService.ToolWorkspaceFile packageJsonFile,
                                   JSONObject packageJson,
                                   String scriptName,
                                   String scriptCommand,
                                   Boolean runInstall) {
        DependencyPolicyService.PolicyDecision decision = dependencyPolicyService.validateScript(scriptName, scriptCommand);
        if (!decision.allowed()) {
            throw toolFailure("错误：依赖策略拒绝 - " + decision.reason());
        }
        JSONObject scripts = packageJson.getJSONObject(SCRIPTS);
        if (scripts == null) {
            scripts = new JSONObject();
            packageJson.set(SCRIPTS, scripts);
        }
        String oldCommand = scripts.getStr(scriptName);
        scripts.set(scriptName, scriptCommand);
        List<String> effectivePaths = writePackageJson(
                appId, packageJsonFile, packageJson, "setScript");
        StringBuilder builder = new StringBuilder();
        builder.append(effectivePaths.isEmpty()
                        ? "脚本已是目标状态，无需重复修改: "
                        : "已设置脚本: ")
                .append(scriptName).append(" -> ").append(scriptCommand);
        if (StrUtil.isNotBlank(oldCommand)) {
            builder.append("（旧命令: ").append(oldCommand).append("）");
        }
        appendDeferredInstallIfRequested(builder, runInstall);
        return new PackageOperationResult(builder.toString(), effectivePaths);
    }

    /** 处理{@code Remove}{@code Script}。 */
    private PackageOperationResult handleRemoveScript(Long appId,
                                      ToolWorkspaceFileService.ToolWorkspaceFile packageJsonFile,
                                      JSONObject packageJson,
                                      String scriptName,
                                      Boolean runInstall) {
        if (StrUtil.isBlank(scriptName)) {
            throw toolFailure("错误：脚本名称不能为空");
        }
        JSONObject scripts = packageJson.getJSONObject(SCRIPTS);
        if (scripts == null || !scripts.containsKey(scriptName)) {
            return PackageOperationResult.confirmedNoMutation(
                    "提示：未找到脚本 - " + scriptName);
        }
        scripts.remove(scriptName);
        List<String> effectivePaths = writePackageJson(
                appId, packageJsonFile, packageJson, "removeScript");
        StringBuilder builder = new StringBuilder("已删除脚本: " + scriptName);
        appendDeferredInstallIfRequested(builder, runInstall);
        return new PackageOperationResult(builder.toString(), effectivePaths);
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
    private List<String> writePackageJson(Long appId,
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
            return result.requireEffectiveChangedPaths();
        }
        throw toolFailure("错误：package.json 写入被拒绝 - " + result.reason());
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
        return withActualToolResult(generateToolExecutedResult(arguments), toolResult);
    }

    private record PackageOperationResult(
            String displayResult,
            List<String> effectivePaths,
            boolean mutationAttempt
    ) {

        private PackageOperationResult {
            displayResult = StrUtil.blankToDefault(displayResult, "操作完成");
            effectivePaths = List.copyOf(effectivePaths == null ? List.of() : effectivePaths);
        }

        private PackageOperationResult(String displayResult, List<String> effectivePaths) {
            this(displayResult, effectivePaths, true);
        }

        private static PackageOperationResult confirmedNoMutation(String displayResult) {
            return new PackageOperationResult(displayResult, List.of(), true);
        }

        private static PackageOperationResult queryResult(String displayResult) {
            return new PackageOperationResult(displayResult, List.of(), false);
        }
    }
}
