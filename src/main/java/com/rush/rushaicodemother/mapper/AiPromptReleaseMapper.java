package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.AiPromptReleaseEntity;
import com.rush.rushaicodemother.model.entity.AiPromptReleaseHistoryEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** Explicit atomic SQL for runtime prompt release pointers and audit history. */
public interface AiPromptReleaseMapper {

    @Select("SELECT revision FROM ai_prompt_release_bundle WHERE id = 1")
    Long selectBundleRevision();

    @Select("SELECT revision FROM ai_prompt_release_bundle WHERE id = 1 FOR UPDATE")
    Long lockBundleRevision();

    @Update("""
            UPDATE ai_prompt_release_bundle
            SET revision = #{nextRevision},
                updatedBy = #{updatedBy},
                updateTime = #{updatedAt}
            WHERE id = 1
              AND revision = #{expectedRevision}
            """)
    int advanceBundle(@Param("expectedRevision") long expectedRevision,
                      @Param("nextRevision") long nextRevision,
                      @Param("updatedBy") long updatedBy,
                      @Param("updatedAt") LocalDateTime updatedAt);

    @Select("""
            SELECT promptKey, stableVersion, canaryVersion, canaryPercentage,
                   revision, updatedBy, changeNote, createTime, updateTime
            FROM ai_prompt_release
            ORDER BY promptKey ASC
            """)
    List<AiPromptReleaseEntity> selectAllCurrent();

    @Select("""
            SELECT promptKey, stableVersion, canaryVersion, canaryPercentage,
                   revision, updatedBy, changeNote, createTime, updateTime
            FROM ai_prompt_release
            WHERE promptKey = #{promptKey}
            LIMIT 1
            FOR UPDATE
            """)
    AiPromptReleaseEntity selectCurrentForUpdate(@Param("promptKey") String promptKey);

    @Insert("""
            INSERT INTO ai_prompt_release (
                promptKey, stableVersion, canaryVersion, canaryPercentage,
                revision, updatedBy, changeNote, createTime, updateTime
            ) VALUES (
                #{promptKey}, #{stableVersion}, #{canaryVersion}, #{canaryPercentage},
                #{revision}, #{updatedBy}, #{changeNote}, #{createTime}, #{updateTime}
            )
            ON DUPLICATE KEY UPDATE
                stableVersion = VALUES(stableVersion),
                canaryVersion = VALUES(canaryVersion),
                canaryPercentage = VALUES(canaryPercentage),
                revision = VALUES(revision),
                updatedBy = VALUES(updatedBy),
                changeNote = VALUES(changeNote),
                updateTime = VALUES(updateTime)
            """)
    int upsertCurrent(AiPromptReleaseEntity entity);

    @Insert("""
            INSERT INTO ai_prompt_release_history (
                revision, promptKey, stableVersion, canaryVersion, canaryPercentage,
                action, sourceRevision, updatedBy, changeNote, evidenceId, createTime
            ) VALUES (
                #{revision}, #{promptKey}, #{stableVersion}, #{canaryVersion}, #{canaryPercentage},
                #{action}, #{sourceRevision}, #{updatedBy}, #{changeNote}, #{evidenceId}, #{createTime}
            )
            """)
    int insertHistory(AiPromptReleaseHistoryEntity entity);

    @Select("""
            SELECT revision, promptKey, stableVersion, canaryVersion, canaryPercentage,
                   action, sourceRevision, updatedBy, changeNote, evidenceId, createTime
            FROM ai_prompt_release_history
            WHERE promptKey = #{promptKey}
              AND revision = #{revision}
            LIMIT 1
            """)
    AiPromptReleaseHistoryEntity selectHistory(@Param("promptKey") String promptKey,
                                               @Param("revision") long revision);

    @Select("""
            SELECT revision, promptKey, stableVersion, canaryVersion, canaryPercentage,
                   action, sourceRevision, updatedBy, changeNote, evidenceId, createTime
            FROM ai_prompt_release_history
            WHERE promptKey = #{promptKey}
            ORDER BY revision DESC
            LIMIT #{limit}
            """)
    List<AiPromptReleaseHistoryEntity> selectHistoryPage(@Param("promptKey") String promptKey,
                                                         @Param("limit") int limit);
}
