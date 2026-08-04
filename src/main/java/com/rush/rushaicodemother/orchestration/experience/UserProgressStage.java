package com.rush.rushaicodemother.orchestration.experience;

/**
 * 面向最终用户的稳定生成阶段。
 *
 * <p>阶段编码属于前后端公共契约；内部智能体、节点、路由和恢复策略不得直接充当用户阶段。</p>
 */
public enum UserProgressStage {

    UNDERSTANDING("understanding", "已理解你的需求", "codegen", false),
    PLANNING("planning", "正在确认修改范围", "codegen", false),
    IMPLEMENTING("implementing", "正在生成或修改代码", "codegen", false),
    PREVIEW_READY("preview_ready", "已可预览", "build", false),
    VERIFYING("verifying", "正在做质量校验", "build", false),
    AWAITING_APPROVAL("awaiting_approval", "需要你确认", "codegen", false),
    DELIVERED("delivered", "已完成交付", "done", true);

    private final String code;
    private final String defaultMessage;
    private final String frontendPhase;
    private final boolean terminal;

    UserProgressStage(String code, String defaultMessage, String frontendPhase, boolean terminal) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.frontendPhase = frontendPhase;
        this.terminal = terminal;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public String getFrontendPhase() {
        return frontendPhase;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
