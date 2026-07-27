package com.rush.rushaicodemother.service.devserver.persistence;

/** 跨节点会话预留的结果。 */
public enum DevServerSessionClaimResult {
    ACQUIRED,
    ACTIVE_SESSION_EXISTS,
    USER_QUOTA_EXCEEDED
}
