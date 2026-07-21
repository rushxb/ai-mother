package com.rush.rushaicodemother.service.devserver.persistence;

/** Result of the cross-node session reservation. */
public enum DevServerSessionClaimResult {
    ACQUIRED,
    ACTIVE_SESSION_EXISTS,
    USER_QUOTA_EXCEEDED
}
