package com.rush.rushaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.ai.tools.policy.DependencyPolicyService;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import com.rush.rushaicodemother.service.dependency.DependencyInstallResult;
import com.rush.rushaicodemother.service.dependency.ProjectDependencyInstaller;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * package.json 管理工具
 */
@Slf4j
@Component
public class PackageManagerTool extends BaseTool {
    private static final String SCRIPTS = "scripts";

    private final DependencyPolicyService dependencyPolicyService;
    private final ToolExecutionGateway toolExecutionGateway;
    private final ProjectDependencyInstaller projectDependencyInstaller;

    public PackageManagerTool(
            DependencyPolicyService dependencyPolicyService,
            ToolExecutionGateway toolExecutionGateway,
            ProjectDependencyInstaller projectDependencyInstaller
    ) {
        this.dependencyPolicyService = dependencyPolicyService;
        this.toolExecutionGateway = toolExecutionGateway;
        this.projectDependencyInstaller = projectDependencyInstaller;
    }

    @Tool("管理 package.json 的依赖和 scripts，支持查看、添加、更新、删除依赖，或修改 scripts；必要时可执行 pnpm install 同步锁文件。")
    public String managePackageJson(
            @P("操作类型：getPackageJson、addDependency、updateDependency、removeDependency、setScript、removeScript、installDependencies")
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
            @P("执行 package.json 修改后是否立即运行 pnpm install，同步 lock 文件")
            Boolean runInstall,
            @P("依赖新增、更新或删除原因；依赖变更时必填")
            String reason,
            @ToolMemoryId Long appId
    ) {
        try {
            Path packageJsonPath = ToolPathSupport.resolvePath("package.json", appId);
            File packageJsonFile = packageJsonPath.toFile();
            if (!packageJsonFile.exists() || !packageJsonFile.isFile()) {
                return "错误：package.json 不存在";
            }
            JSONObject packageJson = JSONUtil.parseObj(FileUtil.readString(packageJsonFile, StandardCharsets.UTF_8));
            String normalizedAction = StrUtil.blankToDefault(action, "getPackageJson");
            return switch (normalizedAction) {
                case "getPackageJson" -> JSONUtil.toJsonPrettyStr(packageJson);
                case "addDependency", "updateDependency" ->
                handleUpsertDependency(appId, packageJsonPath, packageJson, normalizedAction, packageName, version,
                                dependencyType, runInstall, reason);
                case "removeDependency" ->
                        handleRemoveDependency(appId, packageJsonPath, packageJson, packageName, dependencyType, runInstall);
                case "setScript" ->
                        handleSetScript(appId, packageJsonPath, packageJson, scriptName, scriptCommand, runInstall);
                case "removeScript" ->
                        handleRemoveScript(appId, packageJsonPath, packageJson, scriptName, runInstall);
                case "installDependencies" -> runInstall(packageJsonPath.getParent(), "installDependencies");
                default -> "错误：不支持的操作类型 - " + normalizedAction;
            };
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("管理 package.json 失败，action: {}", action, e);
            return "管理 package.json 失败: " + e.getMessage();
        }
    }

    private String handleUpsertDependency(Long appId, Path packageJsonPath, JSONObject packageJson, String action, String packageName,
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
        String writeError = writePackageJson(appId, packageJsonPath, packageJson, action);
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
        appendInstallResultIfNeeded(builder, packageJsonPath.getParent(), runInstall, action);
        return builder.toString();
    }

    private String handleRemoveDependency(Long appId, Path packageJsonPath, JSONObject packageJson, String packageName,
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
        String writeError = writePackageJson(appId, packageJsonPath, packageJson, "removeDependency");
        if (writeError != null) {
            return writeError;
        }
        StringBuilder builder = new StringBuilder("已删除依赖: " + packageName + "（" + sectionName + "）");
        appendInstallResultIfNeeded(builder, packageJsonPath.getParent(), runInstall, "removeDependency");
        return builder.toString();
    }

    private String handleSetScript(Long appId, Path packageJsonPath, JSONObject packageJson, String scriptName, String scriptCommand,
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
        String writeError = writePackageJson(appId, packageJsonPath, packageJson, "setScript");
        if (writeError != null) {
            return writeError;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("已设置脚本: ").append(scriptName).append(" -> ").append(scriptCommand);
        if (StrUtil.isNotBlank(oldCommand)) {
            builder.append("（旧命令: ").append(oldCommand).append("）");
        }
        appendInstallResultIfNeeded(builder, packageJsonPath.getParent(), runInstall, "setScript");
        return builder.toString();
    }

    private String handleRemoveScript(Long appId, Path packageJsonPath, JSONObject packageJson, String scriptName, Boolean runInstall) {
        if (StrUtil.isBlank(scriptName)) {
            return "错误：脚本名称不能为空";
        }
        JSONObject scripts = packageJson.getJSONObject(SCRIPTS);
        if (scripts == null || !scripts.containsKey(scriptName)) {
            return "提示：未找到脚本 - " + scriptName;
        }
        scripts.remove(scriptName);
        String writeError = writePackageJson(appId, packageJsonPath, packageJson, "removeScript");
        if (writeError != null) {
            return writeError;
        }
        StringBuilder builder = new StringBuilder("已删除脚本: " + scriptName);
        appendInstallResultIfNeeded(builder, packageJsonPath.getParent(), runInstall, "removeScript");
        return builder.toString();
    }

    private void appendInstallResultIfNeeded(StringBuilder builder, Path projectDir, Boolean runInstall, String actionSource) {
        if (!Boolean.TRUE.equals(runInstall)) {
            return;
        }
        builder.append("\n\n").append(runInstall(projectDir, actionSource));
    }

    private String runInstall(Path projectDir, String actionSource) {
        DependencyPolicyService.PolicyDecision decision = dependencyPolicyService.validateInstall(actionSource);
        if (!decision.allowed()) {
            return "[pnpm install]\n依赖策略拒绝: " + decision.reason();
        }
        DependencyInstallResult result = projectDependencyInstaller.ensureInstalled(projectDir);
        return formatInstallResult(decision, result);
    }

    private String formatInstallResult(
            DependencyPolicyService.PolicyDecision decision,
            DependencyInstallResult result
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("[pnpm install]\n")
                .append("policy: ").append(decision.reason()).append('\n')
                .append("结果: ").append(result.success() ? "成功" : "失败").append('\n')
                .append("状态: ").append(result.status()).append('\n');
        if (!result.success()) {
            builder.append("异常: ").append(result.errorDetail()).append('\n');
        }
        builder.append("日志:\n")
                .append(StrUtil.isBlank(result.output()) ? "(无输出)" : result.output().trim());
        return builder.toString();
    }

    private String writePackageJson(Long appId, Path packageJsonPath, JSONObject packageJson, String reason) {
        PatchApplyResult result = toolExecutionGateway.applyPatch(
                appId,
                packageJsonPath.getParent(),
                PatchOperation.modify("package.json", JSONUtil.toJsonPrettyStr(packageJson)),
                "tool-package-json",
                reason
        );
        if ("applied".equals(result.status())) {
            return null;
        }
        return "错误：package.json 写入被拒绝 - " + result.reason();
    }

    @Override
    public String getToolName() {
        return "managePackageJson";
    }

    @Override
    public String getDisplayName() {
        return "依赖与脚本管理";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String action = arguments.getStr("action");
        String packageName = arguments.getStr("packageName");
        String scriptName = arguments.getStr("scriptName");
        String target = StrUtil.isNotBlank(packageName) ? packageName : scriptName;
        return String.format("[工具调用] %s %s %s", getDisplayName(), action, StrUtil.blankToDefault(target, ""));
    }

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
