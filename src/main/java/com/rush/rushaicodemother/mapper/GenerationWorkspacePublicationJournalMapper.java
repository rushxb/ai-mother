package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 生成工作区发布日志数据访问映射器。
 */
public interface GenerationWorkspacePublicationJournalMapper {

    @Select("""
            SELECT taskId, appId, executionEpoch,
                   publicationStatus, publicationCodeGenType,
                   publicationExecutionEpoch, publicationPublishedAt,
                   publicationAttempts, publicationVersion, publicationError
            FROM generation_task
            WHERE taskId = #{taskId}
              AND isDelete = 0
            LIMIT 1
            """)
    GenerationTask selectOne(@Param("taskId") String taskId);

    @Update("""
            UPDATE generation_task
            SET publicationStatus = 'prepared',
                publicationCodeGenType = #{codeGenType},
                publicationExecutionEpoch = #{publicationExecutionEpoch},
                publicationPublishedAt = #{publishedAt},
                publicationAttempts = 0,
                publicationVersion = publicationVersion + 1,
                publicationError = NULL,
                publicationReconcileAfter = #{preparedAt},
                publicationCommittedAt = NULL,
                updateTime = #{preparedAt}
            WHERE taskId = #{taskId}
              AND appId = #{appId}
              AND executionEpoch = #{publicationExecutionEpoch}
              AND publicationStatus IS NULL
              AND isDelete = 0
            """)
    int prepareNew(@Param("taskId") String taskId,
                   @Param("appId") Long appId,
                   @Param("codeGenType") String codeGenType,
                   @Param("publicationExecutionEpoch") long publicationExecutionEpoch,
                   @Param("publishedAt") LocalDateTime publishedAt,
                   @Param("preparedAt") LocalDateTime preparedAt);

    @Update("""
            UPDATE generation_task
            SET publicationStatus = 'prepared',
                publicationAttempts = 0,
                publicationVersion = publicationVersion + 1,
                publicationError = NULL,
                publicationReconcileAfter = #{preparedAt},
                publicationCommittedAt = NULL,
                updateTime = #{preparedAt}
            WHERE taskId = #{taskId}
              AND appId = #{appId}
              AND publicationCodeGenType = #{codeGenType}
              AND publicationExecutionEpoch = #{publicationExecutionEpoch}
              AND publicationPublishedAt = #{publishedAt}
              AND publicationStatus = 'rolled_back'
              AND isDelete = 0
            """)
    int reopen(@Param("taskId") String taskId,
               @Param("appId") Long appId,
               @Param("codeGenType") String codeGenType,
               @Param("publicationExecutionEpoch") long publicationExecutionEpoch,
               @Param("publishedAt") LocalDateTime publishedAt,
               @Param("preparedAt") LocalDateTime preparedAt);

    @Update("""
            UPDATE generation_task
            SET publicationStatus = 'filesystem_activated',
                publicationVersion = publicationVersion + 1,
                publicationError = NULL,
                publicationReconcileAfter = #{activatedAt},
                updateTime = #{activatedAt}
            WHERE taskId = #{taskId}
              AND appId = #{appId}
              AND publicationCodeGenType = #{codeGenType}
              AND publicationExecutionEpoch = #{publicationExecutionEpoch}
              AND publicationPublishedAt = #{publishedAt}
              AND publicationStatus IN ('prepared', 'filesystem_activated')
              AND isDelete = 0
            """)
    int markFilesystemActivated(@Param("taskId") String taskId,
                                @Param("appId") Long appId,
                                @Param("codeGenType") String codeGenType,
                                @Param("publicationExecutionEpoch") long publicationExecutionEpoch,
                                @Param("publishedAt") LocalDateTime publishedAt,
                                @Param("activatedAt") LocalDateTime activatedAt);

    @Update("""
            UPDATE generation_task
            SET publicationStatus = 'committed',
                publicationVersion = publicationVersion + 1,
                publicationError = NULL,
                publicationReconcileAfter = NULL,
                publicationCommittedAt = #{committedAt},
                updateTime = #{committedAt}
            WHERE taskId = #{taskId}
              AND appId = #{appId}
              AND publicationCodeGenType = #{codeGenType}
              AND publicationExecutionEpoch = #{publicationExecutionEpoch}
              AND publicationPublishedAt = #{publishedAt}
              AND publicationStatus IN ('prepared', 'filesystem_activated', 'committed')
              AND isDelete = 0
            """)
    int markCommitted(@Param("taskId") String taskId,
                      @Param("appId") Long appId,
                      @Param("codeGenType") String codeGenType,
                      @Param("publicationExecutionEpoch") long publicationExecutionEpoch,
                      @Param("publishedAt") LocalDateTime publishedAt,
                      @Param("committedAt") LocalDateTime committedAt);

    @Update("""
            UPDATE generation_task
            SET publicationStatus = #{status},
                publicationVersion = publicationVersion + 1,
                publicationError = #{error},
                publicationReconcileAfter = NULL,
                updateTime = #{changedAt}
            WHERE taskId = #{taskId}
              AND appId = #{appId}
              AND publicationCodeGenType = #{codeGenType}
              AND publicationExecutionEpoch = #{publicationExecutionEpoch}
              AND publicationPublishedAt = #{publishedAt}
              AND publicationStatus <> 'committed'
              AND isDelete = 0
            """)
    int markTerminal(@Param("taskId") String taskId,
                     @Param("appId") Long appId,
                     @Param("codeGenType") String codeGenType,
                     @Param("publicationExecutionEpoch") long publicationExecutionEpoch,
                     @Param("publishedAt") LocalDateTime publishedAt,
                     @Param("status") String status,
                     @Param("error") String error,
                     @Param("changedAt") LocalDateTime changedAt);

    @Select("""
            SELECT taskId, appId, executionEpoch,
                   publicationStatus, publicationCodeGenType,
                   publicationExecutionEpoch, publicationPublishedAt,
                   publicationAttempts, publicationVersion, publicationError
            FROM generation_task
            WHERE publicationStatus IN ('prepared', 'filesystem_activated', 'rollback_required')
              AND publicationAttempts < #{maxAttempts}
              AND (publicationReconcileAfter IS NULL OR publicationReconcileAfter <= #{now})
              AND isDelete = 0
            ORDER BY COALESCE(publicationReconcileAfter, updateTime) ASC, id ASC
            LIMIT #{limit}
            """)
    List<GenerationTask> selectPending(@Param("now") LocalDateTime now,
                                       @Param("limit") int limit,
                                       @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE generation_task
            SET publicationAttempts = publicationAttempts + 1,
                publicationVersion = publicationVersion + 1,
                publicationError = NULL,
                publicationReconcileAfter = #{retryAt},
                updateTime = #{claimedAt}
            WHERE taskId = #{taskId}
              AND publicationVersion = #{expectedVersion}
              AND publicationStatus IN ('prepared', 'filesystem_activated', 'rollback_required')
              AND publicationAttempts < #{maxAttempts}
              AND (publicationReconcileAfter IS NULL OR publicationReconcileAfter <= #{claimedAt})
              AND isDelete = 0
            """)
    int claim(@Param("taskId") String taskId,
              @Param("expectedVersion") long expectedVersion,
              @Param("maxAttempts") int maxAttempts,
              @Param("claimedAt") LocalDateTime claimedAt,
              @Param("retryAt") LocalDateTime retryAt);

    @Update("""
            UPDATE generation_task
            SET publicationError = #{error},
                updateTime = #{failedAt}
            WHERE taskId = #{taskId}
              AND appId = #{appId}
              AND publicationCodeGenType = #{codeGenType}
              AND publicationExecutionEpoch = #{publicationExecutionEpoch}
              AND publicationPublishedAt = #{publishedAt}
              AND publicationStatus IN ('prepared', 'filesystem_activated', 'rollback_required')
              AND isDelete = 0
            """)
    int recordFailure(@Param("taskId") String taskId,
                      @Param("appId") Long appId,
                      @Param("codeGenType") String codeGenType,
                      @Param("publicationExecutionEpoch") long publicationExecutionEpoch,
                      @Param("publishedAt") LocalDateTime publishedAt,
                      @Param("error") String error,
                      @Param("failedAt") LocalDateTime failedAt);
}
