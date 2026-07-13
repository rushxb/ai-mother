package com.rush.rushaicodemother.service.artifact;

/**
 * 应用本地产物删除事务。
 *
 * <p>激活阶段将当前目录原子移动到同一根目录下的隔离位置；数据库删除成功后提交并
 * 物理清理隔离目录，数据库失败时则回滚恢复原目录。</p>
 */
public interface AppArtifactDeletionTransaction {

    /** 将待删除目录移动到隔离位置。 */
    void activate();

    /** 确认删除并清理隔离目录。 */
    void commit();

    /** 恢复已隔离目录。 */
    void rollback();
}
