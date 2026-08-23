package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;

/**
 * 工作区发布失败及其补偿结果。
 *
 * <p>调用方必须根据 {@link #safelyRolledBack()} 决定是否可以撤销发布前冻结的
 * SUCCESS 终态意图。补偿不完整时可能已有用户可见指针，只能保留原意图并交给
 * journal 对账。</p>
 */
public final class GenerationWorkspacePublicationException extends BusinessException {

    private final boolean safelyRolledBack;

    public GenerationWorkspacePublicationException(boolean safelyRolledBack, Throwable cause) {
        super(errorCode(cause), message(cause));
        this.safelyRolledBack = safelyRolledBack;
        initCause(cause);
    }

    public boolean safelyRolledBack() {
        return safelyRolledBack;
    }

    private static int errorCode(Throwable cause) {
        return cause instanceof BusinessException businessException
                ? businessException.getCode()
                : ErrorCode.SYSTEM_ERROR.getCode();
    }

    private static String message(Throwable cause) {
        return cause instanceof BusinessException && cause.getMessage() != null
                ? cause.getMessage()
                : "Execution workspace publication failed";
    }
}
