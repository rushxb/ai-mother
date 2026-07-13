package com.rush.rushaicodemother.service.artifact;

/**
 * 部署目录替换事务。
 *
 * <p>文件系统不参与数据库事务，因此通过准备、激活、提交和回滚四个阶段，
 * 保证数据库更新失败时能够恢复原部署目录。</p>
 */
public interface DeploymentArtifactTransaction {

    /** 将已准备的暂存目录切换为当前部署目录。 */
    void activate();

    /** 确认本次目录替换，并清理旧版本备份。 */
    void commit();

    /** 撤销目录替换并恢复旧版本；尚未激活时仅清理暂存目录。 */
    void rollback();
}
