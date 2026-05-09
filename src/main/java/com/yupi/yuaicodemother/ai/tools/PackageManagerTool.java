package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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

    private static final String DEPENDENCIES = "dependencies";
    private static final String DEV_DEPENDENCIES = "devDependencies";
    private static final String SCRIPTS = "scripts";

    @Tool("管理 package.json 的依赖和 scripts，支持查看、添加、更新、删除依赖，或修改 npm scripts；必要时可执行 npm install 同步锁文件。")
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
            @P("执行 package.json 修改后是否立即运行 npm install，同步 lock 文件")
            Boolean runInstall,
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
                        handleUpsertDependency(packageJsonPath, packageJson, normalizedAction, packageName, version,
                                dependencyType, runInstall);
                case "removeDependency" ->
                        handleRemoveDependency(packageJsonPath, packageJson, packageName, dependencyType, runInstall);
                case "setScript" ->
                        handleSetScript(packageJsonPath, packageJson, scriptName, scriptCommand, runInstall);
                case "removeScript" ->
                        handleRemoveScript(packageJsonPath, packageJson, scriptName, runInstall);
                case "installDependencies" -> runInstall(packageJsonPath.getParent());
                default -> "错误：不支持的操作类型 - " + normalizedAction;
            };
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("管理 package.json 失败，action: {}", action, e);
            return "管理 package.json 失败: " + e.getMessage();
        }
    }

    private String handleUpsertDependency(Path packageJsonPath, JSONObject packageJson, String action, String packageName,
                                          String version, String dependencyType, Boolean runInstall) {
        if (StrUtil.isBlank(packageName) || StrUtil.isBlank(version)) {
            return "错误：依赖名称和版本不能为空";
        }
        String sectionName = normalizeDependencyType(dependencyType);
        JSONObject section = packageJson.getJSONObject(sectionName);
        if (section == null) {
            section = new JSONObject();
            packageJson.set(sectionName, section);
        }
        String oldVersion = section.getStr(packageName);
        section.set(packageName, version);
        writePackageJson(packageJsonPath, packageJson);
        StringBuilder builder = new StringBuilder();
        builder.append(action.equals("addDependency") ? "已添加依赖" : "已更新依赖")
                .append(": ")
                .append(packageName)
                .append(" -> ")
                .append(version);
        if (StrUtil.isNotBlank(oldVersion)) {
            builder.append("（旧版本: ").append(oldVersion).append("）");
        }
        appendInstallResultIfNeeded(builder, packageJsonPath.getParent(), runInstall);
        return builder.toString();
    }

    private String handleRemoveDependency(Path packageJsonPath, JSONObject packageJson, String packageName,
                                          String dependencyType, Boolean runInstall) {
        if (StrUtil.isBlank(packageName)) {
            return "错误：依赖名称不能为空";
        }
        String sectionName = normalizeDependencyType(dependencyType);
        JSONObject section = packageJson.getJSONObject(sectionName);
        if (section == null || !section.containsKey(packageName)) {
            return "提示：在 " + sectionName + " 中未找到依赖 - " + packageName;
        }
        section.remove(packageName);
        writePackageJson(packageJsonPath, packageJson);
        StringBuilder builder = new StringBuilder("已删除依赖: " + packageName + "（" + sectionName + "）");
        appendInstallResultIfNeeded(builder, packageJsonPath.getParent(), runInstall);
        return builder.toString();
    }

    private String handleSetScript(Path packageJsonPath, JSONObject packageJson, String scriptName, String scriptCommand,
                                   Boolean runInstall) {
        if (StrUtil.isBlank(scriptName) || StrUtil.isBlank(scriptCommand)) {
            return "错误：脚本名称和脚本命令不能为空";
        }
        JSONObject scripts = packageJson.getJSONObject(SCRIPTS);
        if (scripts == null) {
            scripts = new JSONObject();
            packageJson.set(SCRIPTS, scripts);
        }
        String oldCommand = scripts.getStr(scriptName);
        scripts.set(scriptName, scriptCommand);
        writePackageJson(packageJsonPath, packageJson);
        StringBuilder builder = new StringBuilder();
        builder.append("已设置脚本: ").append(scriptName).append(" -> ").append(scriptCommand);
        if (StrUtil.isNotBlank(oldCommand)) {
            builder.append("（旧命令: ").append(oldCommand).append("）");
        }
        appendInstallResultIfNeeded(builder, packageJsonPath.getParent(), runInstall);
        return builder.toString();
    }

    private String handleRemoveScript(Path packageJsonPath, JSONObject packageJson, String scriptName, Boolean runInstall) {
        if (StrUtil.isBlank(scriptName)) {
            return "错误：脚本名称不能为空";
        }
        JSONObject scripts = packageJson.getJSONObject(SCRIPTS);
        if (scripts == null || !scripts.containsKey(scriptName)) {
            return "提示：未找到脚本 - " + scriptName;
        }
        scripts.remove(scriptName);
        writePackageJson(packageJsonPath, packageJson);
        StringBuilder builder = new StringBuilder("已删除脚本: " + scriptName);
        appendInstallResultIfNeeded(builder, packageJsonPath.getParent(), runInstall);
        return builder.toString();
    }

    private void appendInstallResultIfNeeded(StringBuilder builder, Path projectDir, Boolean runInstall) {
        if (!Boolean.TRUE.equals(runInstall)) {
            return;
        }
        builder.append("\n\n").append(runInstall(projectDir));
    }

    private String runInstall(Path projectDir) {
        NpmCommandSupport.CommandResult result = NpmCommandSupport.runCommand(
                projectDir, 300, NpmCommandSupport.npmCommand(), "install"
        );
        return "[npm install]\n" + result.toReport();
    }

    private void writePackageJson(Path packageJsonPath, JSONObject packageJson) {
        FileUtil.writeString(JSONUtil.toJsonPrettyStr(packageJson), packageJsonPath.toFile(), StandardCharsets.UTF_8);
    }

    private String normalizeDependencyType(String dependencyType) {
        if (DEV_DEPENDENCIES.equals(dependencyType)) {
            return DEV_DEPENDENCIES;
        }
        return DEPENDENCIES;
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
