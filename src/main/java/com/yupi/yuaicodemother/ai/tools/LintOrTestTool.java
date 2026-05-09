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
import java.util.Set;

/**
 * Lint / Test / Type Check 工具
 */
@Slf4j
@Component
public class LintOrTestTool extends BaseTool {

    private static final Set<String> ALLOWED_SCRIPT_PREFIXES = Set.of(
            "lint", "test", "type-check", "check", "build"
    );

    @Tool("执行项目中的 lint、test、type-check、build 等校验脚本，只允许运行 package.json 里已存在的白名单脚本。")
    public String runProjectCheck(
            @P("要执行的 script 名称，例如 lint、test、type-check、build、lint:fix")
            String scriptName,
            @P("可选，相对项目子目录；为空则在项目根目录执行")
            String relativeProjectPath,
            @ToolMemoryId Long appId
    ) {
        if (StrUtil.isBlank(scriptName)) {
            return "错误：script 名称不能为空";
        }
        try {
            Path projectPath = ToolPathSupport.resolvePath(relativeProjectPath, appId);
            File projectDir = projectPath.toFile();
            if (!projectDir.exists() || !projectDir.isDirectory()) {
                return "错误：项目目录不存在 - " + relativeProjectPath;
            }
            if (!isAllowedScriptName(scriptName)) {
                return "错误：仅允许运行 lint、test、type-check、check、build 相关脚本";
            }
            Path packageJsonPath = projectPath.resolve("package.json");
            if (!packageJsonPath.toFile().exists()) {
                return "错误：package.json 不存在";
            }
            JSONObject packageJson = JSONUtil.parseObj(FileUtil.readString(packageJsonPath.toFile(), StandardCharsets.UTF_8));
            JSONObject scripts = packageJson.getJSONObject("scripts");
            if (scripts == null || !scripts.containsKey(scriptName)) {
                return "错误：package.json 中未找到脚本 - " + scriptName;
            }
            NpmCommandSupport.CommandResult result = NpmCommandSupport.runCommand(
                    projectPath, 300, NpmCommandSupport.npmCommand(), "run", scriptName
            );
            StringBuilder builder = new StringBuilder();
            builder.append("脚本: ").append(scriptName).append('\n');
            builder.append("命令结果: ").append(result.toSingleLineSummary()).append("\n\n");
            builder.append(result.toReport());
            return builder.toString().trim();
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("执行项目校验失败，scriptName: {}", scriptName, e);
            return "执行项目校验失败: " + e.getMessage();
        }
    }

    private boolean isAllowedScriptName(String scriptName) {
        return ALLOWED_SCRIPT_PREFIXES.stream()
                .anyMatch(prefix -> scriptName.equals(prefix) || scriptName.startsWith(prefix + ":"));
    }

    @Override
    public String getToolName() {
        return "runProjectCheck";
    }

    @Override
    public String getDisplayName() {
        return "Lint/Test 校验";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("[工具调用] %s %s", getDisplayName(), arguments.getStr("scriptName"));
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments, String toolResult) {
        return generateToolExecutedResult(arguments) + "\n" + summarizeResult(toolResult, 320);
    }

    private String summarizeResult(String toolResult, int maxChars) {
        if (StrUtil.isBlank(toolResult)) {
            return "";
        }
        String normalized = toolResult.replace("\r", " ").replace("\n", " ").trim();
        return StrUtil.sub(normalized, 0, Math.min(normalized.length(), maxChars));
    }
}
