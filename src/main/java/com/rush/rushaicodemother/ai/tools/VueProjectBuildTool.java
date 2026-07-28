package com.rush.rushaicodemother.ai.tools;

import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.core.builder.VueBuildResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Vue 项目本地构建诊断工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VueProjectBuildTool extends BaseTool {

    private final VueProjectBuilder vueProjectBuilder;
    private final ToolPathSupport toolPathSupport;

    /**
 * 执行 Vue 项目构建并返回诊断结果。
 *
 * @param relativeProjectPath 项目相对路径
 * @param appId 应用编号
 * @return 处理后的方法执行结果文本
 */
    @Tool("执行本地 Vue 项目构建，返回 pnpm install 和 pnpm run build 的详细结果。生成完成后、构建失败后、或用户反馈项目有问题时必须优先调用此工具。")
    public String buildVueProject(
            @P("可选，相对于当前项目根目录的子目录。为空时默认构建整个项目根目录")
            String relativeProjectPath,
            @ToolMemoryId Long appId
    ) {
        String projectPath = resolveProjectPath(relativeProjectPath, appId);
        String taskId = toolPathSupport.resolveTaskId(appId);
        log.info("开始执行 Vue 项目构建诊断，appId: {}, projectPath: {}", appId, projectPath);
        VueBuildResult buildResult = vueProjectBuilder.buildProjectWithResult(projectPath, taskId);
        return buildResult.toPublicDiagnosticReport();
    }

    private String resolveProjectPath(String relativeProjectPath, Long appId) {
        return toolPathSupport.resolvePath(relativeProjectPath, appId).toString();
    }

    @Override
    public ToolRiskLevel getRiskLevel() {
        return ToolRiskLevel.EXTERNAL_SIDE_EFFECT;
    }

    @Override
    public String getToolName() {
        return "buildVueProject";
    }

    @Override
    public String getDisplayName() {
        return "本地构建诊断";
    }

    /**
 * 将工具执行结果整理为模型可消费的文本。
 *
 * @param arguments 参数
 * @return 处理后的方法执行结果文本
 */
    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeProjectPath = arguments.getStr("relativeProjectPath");
        if (relativeProjectPath == null || relativeProjectPath.isBlank()) {
            relativeProjectPath = "项目根目录";
        }
        return String.format("[工具调用] %s %s", getDisplayName(), relativeProjectPath);
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
        if (toolResult == null || toolResult.isBlank()) {
            return "";
        }
        String normalized = toolResult.replace("\r", " ").replace("\n", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), maxChars));
    }
}
