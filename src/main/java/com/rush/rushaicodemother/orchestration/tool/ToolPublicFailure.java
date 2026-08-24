package com.rush.rushaicodemother.orchestration.tool;

/**
 * 标记允许安全返回给模型的工具失败。
 *
 * <p>输入校验与执行拒绝拥有不同的异常继承关系，但共享同一公开文案契约；
 * Agent 协议只依赖该接口，不依赖具体工具异常类型。</p>
 */
public interface ToolPublicFailure {

    String publicMessage();

    /** 校验并规范化公开文案，禁止构造无意义的协议失败。 */
    static String requirePublicMessage(String publicMessage) {
        if (publicMessage == null || publicMessage.isBlank()) {
            throw new IllegalArgumentException("工具失败的公开文案不能为空");
        }
        return publicMessage.trim();
    }
}
