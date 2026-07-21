package com.rush.rushaicodemother.service.dependency;

/**
 * Declares how dependency state may change during one installation request.
 *
 * <p>Keeping the policy explicit prevents build validation from silently rewriting lockfiles,
 * while package-management tools can intentionally update them as part of an approved change.</p>
 */
public enum DependencyInstallMode {

    /** Reuse a verified node_modules directory; otherwise install exactly from the lockfile. */
    REUSE_IF_VALID(true, true),

    /** Always refresh node_modules, but never mutate the lockfile. */
    REFRESH_FROM_LOCKFILE(false, true),

    /** Re-resolve dependencies and update the lockfile after an intentional package.json change. */
    UPDATE_LOCKFILE(false, false);

    private final boolean reuseIfValid;
    private final boolean frozenLockfile;

    DependencyInstallMode(boolean reuseIfValid, boolean frozenLockfile) {
        this.reuseIfValid = reuseIfValid;
        this.frozenLockfile = frozenLockfile;
    }

    public boolean reuseIfValid() {
        return reuseIfValid;
    }

    public boolean frozenLockfile() {
        return frozenLockfile;
    }
}
