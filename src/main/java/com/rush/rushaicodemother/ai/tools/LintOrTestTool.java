package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.PublicDiagnosticSanitizer;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandExecutor;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    private final ProjectCommandExecutor projectCommandExecutor;
    private final ProjectCommandProperties projectCommandProperties;
    private final ToolWorkspaceFileService workspaceFileService;

    public LintOrTestTool(
            ProjectCommandExecutor projectCommandExecutor,
            ProjectCommandProperties projectCommandProperties,
            ToolWorkspaceFileService workspaceFileService
    ) {
        this.projectCommandExecutor = projectCommandExecutor;
        this.projectCommandProperties = projectCommandProperties;
        this.workspaceFileService = workspaceFileService;
    }

    /**
 * 在目标项目中运行指定的校验脚本。
 *
 * @param scriptName 待执行脚本名称
 * @param relativeProjectPath 项目相对路径
 * @param appId 应用编号
 * @return 处理后的方法执行结果文本
 */
    @Tool("执行项目中的 lint、test、type-check、build 等校验脚本，只允许运行 package.json 里已存在的白名单脚本。")
    public String runProjectCheck(
            @P("要执行的 script 名称，例如 lint、test、type-check、build、lint:fix")
            String scriptName,
            @P("可选，相对项目子目录；为空则在项目根目录执行")
            String relativeProjectPath,
            @ToolMemoryId Long appId
    ) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (StrUtil.isBlank(scriptName)) {
            return "错误：script 名称不能为空";
        }
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            ToolWorkspaceFileService.ToolWorkspaceDirectory projectDirectory =
                    workspaceFileService.resolveDirectory(appId, relativeProjectPath);
            if (!isAllowedScriptName(scriptName)) {
                return "错误：仅允许运行 lint、test、type-check、check、build 相关脚本";
            }
            ToolWorkspaceFileService.ToolWorkspaceFile packageJsonFile =
                    workspaceFileService.resolveFile(projectDirectory, "package.json");
            if (!workspaceFileService.isRegularFile(packageJsonFile)) {
                return "错误：package.json 不存在";
            }
            JSONObject packageJson = JSONUtil.parseObj(workspaceFileService.readUtf8(packageJsonFile));
            JSONObject scripts = packageJson.getJSONObject("scripts");
            if (scripts == null || !scripts.containsKey(scriptName)) {
                return "错误：package.json 中未找到脚本 - " + scriptName;
            }
            ProjectCommandResult result = projectCommandExecutor.executePnpmScript(
                    projectDirectory.absolutePath(),
                    scriptName,
                    projectCommandProperties.getToolScriptTimeout(),
                    workspaceFileService.requireTaskId(appId),
                    "tool-check:" + scriptName
            );
            StringBuilder builder = new StringBuilder();
            builder.append("脚本: ").append(scriptName).append('\n');
            builder.append("命令结果: ").append(toSingleLineSummary(result)).append("\n\n");
            builder.append(toReport(result));
            return PublicDiagnosticSanitizer.sanitizeForPublicOutput(builder.toString().trim());
        } catch (ToolInputException e) {
            return renderInputError(e);
        } catch (Exception e) {
            log.error("执行项目校验失败，scriptName: {}", scriptName, LogExceptionSanitizer.sanitize(e));
            return "执行项目校验失败，请稍后重试";
        }
    }

    private boolean isAllowedScriptName(String scriptName) {
        return ALLOWED_SCRIPT_PREFIXES.stream()
                .anyMatch(prefix -> scriptName.equals(prefix) || scriptName.startsWith(prefix + ":"));
    }

    /** 将当前对象转换为报告。 */
    private String toReport(ProjectCommandResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("命令: ").append(result.command()).append('\n');
        builder.append("结果: ").append(result.success() ? "成功" : "失败").append('\n');
        if (result.exitCode() != null) {
            builder.append("退出码: ").append(result.exitCode()).append('\n');
        }
        if (result.timedOut()) {
            builder.append("超时: 是").append('\n');
        }
        if (StrUtil.isNotBlank(result.errorDetail())) {
            builder.append("异常: ").append(result.errorDetail()).append('\n');
        }
        builder.append("日志:\n")
                .append(StrUtil.isBlank(result.output()) ? "(无输出)" : result.output().trim());
        return builder.toString();
    }

    /** 将当前对象转换为{@code Single}{@code Line}汇总。 */
    private String toSingleLineSummary(ProjectCommandResult result) {
        StringBuilder builder = new StringBuilder(result.success() ? "成功" : "失败");
        if (result.exitCode() != null) {
            builder.append("，退出码=").append(result.exitCode());
        }
        if (result.timedOut()) {
            builder.append("，超时");
        }
        if (StrUtil.isNotBlank(result.errorDetail())) {
            builder.append("，异常=").append(result.errorDetail());
        }
        return builder.toString();
    }

    @Override
    public ToolRiskLevel getRiskLevel() {
        return ToolRiskLevel.EXTERNAL_SIDE_EFFECT;
    }

    @Override
    public String getToolName() {
        return "runProjectCheck";
    }

    @Override
    public String getDisplayName() {
        return "Lint/Test 校验";
    }

    /**
 * 将工具执行结果整理为模型可消费的文本。
 *
 * @param arguments 参数
 * @return 处理后的方法执行结果文本
 */
    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("[工具调用] %s %s", getDisplayName(), arguments.getStr("scriptName"));
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
