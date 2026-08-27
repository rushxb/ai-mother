package com.rush.rushaicodemother.ai.tools;

import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.diagnostic.PublicDiagnosticSanitizer;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;

/**
 * 工具基类
 * 定义所有工具的通用接口
 */
public abstract class BaseTool {

    private static final int MAX_PUBLIC_TOOL_RESULT_LENGTH = 320;

    /**
     * 输出经过显式安全标记的输入校验错误。
     */
    protected final String renderInputError(ToolInputException exception) {
        return renderInputError("错误：", exception);
    }

    /**
     * 使用工具特定前缀输出经过显式安全标记的输入校验错误。
     */
    protected final String renderInputError(String prefix, ToolInputException exception) {
        String safePrefix = prefix == null ? "" : prefix;
        return safePrefix + exception.publicMessage();
    }

    /**
     * 业务异常只在非系统错误时保留明确业务文案；系统错误统一返回稳定文案。
     */
    protected final String renderBusinessError(BusinessException exception, String fallbackMessage) {
        if (exception.getCode() == ErrorCode.SYSTEM_ERROR.getCode()
                || exception.getMessage() == null
                || exception.getMessage().isBlank()) {
            return fallbackMessage;
        }
        return "错误：" + exception.getMessage();
    }

    /** 构造可由 Agent 协议标记为失败、且允许安全返回给模型的工具结果。 */
    protected final ToolPublicFailureException toolFailure(String publicMessage) {
        return new ToolPublicFailureException(publicMessage);
    }

    /** 将工具输入异常转换为协议级失败，同时保留经过审核的可操作文案。 */
    protected final ToolPublicFailureException toolInputFailure(String prefix, ToolInputException exception) {
        return toolFailure(renderInputError(prefix, exception));
    }

    /**
     * 获取工具的英文名称（对应方法名）
     *
     * @return 工具英文名称
     */
    public abstract String getToolName();

    /**
     * 获取工具的中文显示名称
     *
     * @return 工具中文名称
     */
    public abstract String getDisplayName();

    /** 此工具暴露的任何操作的最大风险。 */
    public abstract ToolRiskLevel getRiskLevel();

    /** 当前工具是否具备修改生成工作区内容的能力。 */
    public boolean canMutateWorkspace() {
        return false;
    }

    /**
     * 声明本工具能否暴露给指定工程类型的代码生成 Agent。
     *
     * <p>通用读写工具默认支持所有已注册工程类型；依赖、构建或运行时专用工具
     * 必须在自身 adapter 中收窄能力，避免 ToolManager 维护易漂移的工具名称表。</p>
     */
    public boolean supportsCodeGeneration(CodeGenTypeEnum codeGenType) {
        return codeGenType != null;
    }

    /**
     * 生成工具请求时的返回值（显示给用户）
     *
     * @return 工具请求显示内容
     */
    public String generateToolRequestResponse() {
        return String.format("\n\n[选择工具] %s\n\n", getDisplayName());
    }

    /**
     * 生成工具执行结果格式（保存到数据库）
     *
     * @param arguments 工具执行参数
     * @return 格式化的工具执行结果
     */
    public abstract String generateToolExecutedResult(JSONObject arguments);

    /**
     * 生成工具执行结果格式（包含工具真实返回结果）
     *
     * @param arguments  工具执行参数
     * @param toolResult 工具真实执行结果
     * @return 格式化的工具执行结果
     */
    public String generateToolExecutedResult(JSONObject arguments, String toolResult) {
        return generateToolExecutedResult(arguments);
    }

    /**
     * 在工具标题后追加经过脱敏和长度限制的真实执行结果。
     *
     * <p>写工具不能只凭请求参数渲染“已落盘”；真实结果可能是幂等 no-op。</p>
     */
    protected final String withActualToolResult(String title, String toolResult) {
        String safeTitle = title == null ? "[工具调用] 执行完成" : title;
        String summary = PublicDiagnosticSanitizer.sanitizeSingleLine(
                toolResult, MAX_PUBLIC_TOOL_RESULT_LENGTH);
        return summary.isBlank() ? safeTitle : safeTitle + "\n" + summary;
    }
} 
