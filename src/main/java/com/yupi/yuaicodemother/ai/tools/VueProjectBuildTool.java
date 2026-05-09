package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.json.JSONObject;
import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.core.builder.VueProjectBuilder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Vue 项目本地构建诊断工具
 */
@Slf4j
@Component
public class VueProjectBuildTool extends BaseTool {

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Tool("执行本地 Vue 项目构建，返回 npm install 和 npm run build 的详细结果。生成完成后、构建失败后、或用户反馈项目有问题时必须优先调用此工具。")
    public String buildVueProject(
            @P("可选，相对于当前项目根目录的子目录。为空时默认构建整个项目根目录")
            String relativeProjectPath,
            @ToolMemoryId Long appId
    ) {
        String projectPath = resolveProjectPath(relativeProjectPath, appId);
        log.info("开始执行 Vue 项目构建诊断，appId: {}, projectPath: {}", appId, projectPath);
        VueProjectBuilder.BuildResult buildResult = vueProjectBuilder.buildProjectWithResult(projectPath);
        return buildResult.toDiagnosticReport();
    }

    private String resolveProjectPath(String relativeProjectPath, Long appId) {
        String projectDirName = "vue_project_" + appId;
        Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName);
        if (relativeProjectPath == null || relativeProjectPath.isBlank()) {
            return projectRoot.toString();
        }
        Path path = Paths.get(relativeProjectPath);
        if (path.isAbsolute()) {
            return path.normalize().toString();
        }
        return projectRoot.resolve(relativeProjectPath).normalize().toString();
    }

    @Override
    public String getToolName() {
        return "buildVueProject";
    }

    @Override
    public String getDisplayName() {
        return "本地构建诊断";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeProjectPath = arguments.getStr("relativeProjectPath");
        if (relativeProjectPath == null || relativeProjectPath.isBlank()) {
            relativeProjectPath = "项目根目录";
        }
        return String.format("[工具调用] %s %s", getDisplayName(), relativeProjectPath);
    }

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
