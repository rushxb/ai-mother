package com.rush.rushaicodemother.orchestration.tool;

/**
 * 表示允许安全返回给模型的工具失败。
 *
 * <p>该异常是工具实现与 Agent 协议之间的信任边界：只有服务端主动构造、
 * 不含内部路径、密钥、Provider 响应或底层异常文本的文案才可进入此类型。
 * 未归类异常仍由 {@link ToolExecutionFailurePolicy} 统一脱敏。</p>
 */
public class ToolPublicFailureException extends RuntimeException implements ToolPublicFailure {

    private final String publicMessage;

    public ToolPublicFailureException(String publicMessage) {
        super(ToolPublicFailure.requirePublicMessage(publicMessage));
        this.publicMessage = getMessage();
    }

    public String publicMessage() {
        return publicMessage;
    }
}
