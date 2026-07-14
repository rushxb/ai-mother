package com.rush.rushaicodemother.exception;

/**
 * 可恢复的下游服务或本地保护容量暂时不可用。
 *
 * <p>异常消息用于服务端诊断，{@link #getPublicMessage()} 才允许返回给客户端，
 * 从类型层面避免将连接信息、容量配置或其他内部细节暴露到接口响应。</p>
 */
public abstract class ServiceUnavailableException extends RuntimeException {

    private final String publicMessage;

    protected ServiceUnavailableException(String publicMessage, String diagnosticMessage) {
        super(requireText(diagnosticMessage, "diagnosticMessage"));
        this.publicMessage = requireText(publicMessage, "publicMessage");
    }

    public String getPublicMessage() {
        return publicMessage;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }
}
