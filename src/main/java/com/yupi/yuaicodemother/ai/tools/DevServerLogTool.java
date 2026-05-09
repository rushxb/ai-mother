package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 开发服务器日志工具
 */
@Component
public class DevServerLogTool extends BaseTool {

    @Resource
    private LocalDevServerManager localDevServerManager;

    @Tool("启动、重启、查看或停止本地 npm run dev 开发服务器，返回启动状态、访问地址和最近日志，适合排查白屏、热更新失败、启动失败等运行时问题。")
    public String manageDevServer(
            @P("操作类型：startDevServer、restartDevServer、getDevServerStatus、stopDevServer")
            String action,
            @P("可选，相对项目子目录；为空则针对整个项目根目录")
            String relativeProjectPath,
            @ToolMemoryId Long appId
    ) {
        String normalizedAction = StrUtil.blankToDefault(action, "getDevServerStatus");
        try {
            return switch (normalizedAction) {
                case "startDevServer" -> {
                    Path projectPath = ToolPathSupport.resolvePath(relativeProjectPath, appId);
                    yield localDevServerManager.startServer(appId, projectPath);
                }
                case "restartDevServer" -> {
                    Path projectPath = ToolPathSupport.resolvePath(relativeProjectPath, appId);
                    yield localDevServerManager.restartServer(appId, projectPath);
                }
                case "getDevServerStatus" -> localDevServerManager.getServerStatus(appId);
                case "stopDevServer" -> localDevServerManager.stopServer(appId);
                default -> "错误：不支持的操作类型 - " + normalizedAction;
            };
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            return "开发服务器操作失败: " + e.getMessage();
        }
    }

    @Override
    public String getToolName() {
        return "manageDevServer";
    }

    @Override
    public String getDisplayName() {
        return "开发服务器日志";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("[工具调用] %s %s", getDisplayName(), arguments.getStr("action"));
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
