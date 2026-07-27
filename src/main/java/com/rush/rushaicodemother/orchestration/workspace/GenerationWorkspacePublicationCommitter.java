package com.rush.rushaicodemother.orchestration.workspace;

/** 在一个数据库事务中提交关系发布元数据和日志状态。 */
@FunctionalInterface
public interface GenerationWorkspacePublicationCommitter {

    void commit(GenerationWorkspacePublicationPointer pointer);
}
