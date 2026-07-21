package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.service.devserver.DevServerAppTargetLookup;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import com.rush.rushaicodemother.service.devserver.DevServerStartResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Dev Server 生命周期和最近输出诊断工具。
 *
 * <p>所有启动、停止、端口和输出状态均委托给统一的 {@link DevServerManager}，
 * 不再维护独立进程、端口或日志缓冲。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DevServerLogTool extends BaseTool {

    private static final int RECENT_OUTPUT_LIMIT = 80;

    private final DevServerAppTargetLookup appTargetLookup;
    private final DevServerManager devServerManager;

    @Tool("启动、重启、查看或停止本地 Dev Server，返回启动状态、回环访问地址和最近输出，适合排查白屏、热更新失败与启动失败。")
    public String manageDevServer(
            @P("操作类型：startDevServer、restartDevServer、getDevServerStatus、stopDevServer")
            String action,
            @ToolMemoryId Long appId
    ) {
        String normalizedAction = StrUtil.blankToDefault(action, "getDevServerStatus");
        try {
            validateAppId(appId);
            return switch (normalizedAction) {
                case "startDevServer" -> startServer(appId, false);
                case "restartDevServer" -> startServer(appId, true);
                case "getDevServerStatus" -> renderStatus("Dev Server 状态", appId, null);
                case "stopDevServer" -> stopServer(appId);
                default -> "错误：不支持的操作类型 - " + normalizedAction;
            };
        } catch (ToolInputException exception) {
            return renderInputError(exception);
        } catch (BusinessException exception) {
            return renderBusinessError(exception, "Dev Server 操作失败，请稍后重试");
        } catch (RuntimeException exception) {
            log.error("Dev Server 工具执行失败，action: {}, appId: {}", normalizedAction, appId,
                    LogExceptionSanitizer.sanitize(exception));
            return "Dev Server 操作失败，请稍后重试";
        }
    }

    private String startServer(Long appId, boolean restart) {
        App app = appTargetLookup.requireTarget(appId);
        Long ownerId = app.getUserId();
        if (ownerId == null || ownerId <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用所有者信息无效");
        }
        if (restart) {
            devServerManager.stopDevServer(appId);
        }
        DevServerStartResult result = devServerManager.startDevServer(app, ownerId);
        String title = restart ? "Dev Server 重启结果" : "Dev Server 启动结果";
        return renderStatus(title, appId, result.port());
    }

    private String stopServer(Long appId) {
        Integer runningPort = devServerManager.getPort(appId);
        devServerManager.stopDevServer(appId);
        if (runningPort == null) {
            return "当前没有运行中的 Dev Server";
        }
        return "Dev Server 已停止，端口: " + runningPort;
    }

    private String renderStatus(String title, Long appId, Integer fallbackPort) {
        Integer runningPort = devServerManager.getPort(appId);
        Integer reportPort = runningPort != null ? runningPort : fallbackPort;
        List<String> recentLines = devServerManager.getRecentOutputLines(appId, RECENT_OUTPUT_LIMIT);

        StringBuilder report = new StringBuilder();
        report.append(title).append('\n');
        report.append("状态: ").append(runningPort == null ? "未运行" : "运行中").append('\n');
        if (reportPort != null) {
            report.append("URL: http://127.0.0.1:").append(reportPort).append('/').append('\n');
            report.append("端口: ").append(reportPort).append('\n');
        }
        report.append("最近输出:\n");
        if (recentLines.isEmpty()) {
            report.append("(暂无输出)");
        } else {
            recentLines.forEach(line -> report.append(line).append('\n'));
        }
        return report.toString().trim();
    }

    private void validateAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new ToolInputException("应用 ID 必须大于 0");
        }
    }

    @Override
    public ToolRiskLevel getRiskLevel() {
        return ToolRiskLevel.EXTERNAL_SIDE_EFFECT;
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
