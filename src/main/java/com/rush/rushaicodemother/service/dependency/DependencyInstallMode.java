package com.rush.rushaicodemother.service.dependency;

/**
 * 声明依赖关系状态在一个安装请求期间可能如何改变。
 *
 * <p>保持策略明确可以防止构建验证默默地重写锁定文件，
 * 虽然包管理工具可以有意更新它们作为批准的更改的一部分。</p>
 */
public enum DependencyInstallMode {

    /** 重用经过验证的node_modules目录；否则完全从锁定文件安装。 */
    REUSE_IF_VALID(true, true),

    /** 始终刷新node_modules，但永远不要改变锁文件。 */
    REFRESH_FROM_LOCKFILE(false, true),

    /** 在故意更改 package.json 后重新解决依赖关系并更新锁定文件。 */
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
