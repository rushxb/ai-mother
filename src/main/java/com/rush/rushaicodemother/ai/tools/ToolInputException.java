package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailure;

/**
 * 表示可安全返回给工具调用方的输入校验错误。
 *
 * <p>只有由服务端显式构造、且不包含内部路径、密钥、Provider 响应或底层异常文本的消息，
 * 才允许使用该异常。其他异常必须记录到内部日志，并返回稳定的工具级失败文案。</p>
 */
public final class ToolInputException extends IllegalArgumentException implements ToolPublicFailure {

    private final String publicMessage;

    public ToolInputException(String publicMessage) {
        super(ToolPublicFailure.requirePublicMessage(publicMessage));
        this.publicMessage = getMessage();
    }

    public ToolInputException(String publicMessage, Throwable cause) {
        super(ToolPublicFailure.requirePublicMessage(publicMessage), cause);
        this.publicMessage = getMessage();
    }

    @Override
    public String publicMessage() {
        return publicMessage;
    }
}
