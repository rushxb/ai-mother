package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationControlAuditEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** generation_control_audit_event 的显式 SQL 边界。 */
public interface GenerationControlAuditMapper {

    @Insert("""
            INSERT INTO generation_control_audit_event (
                eventId, permission, resourceType, resourceId,
                actorType, actorUserId, transport, outcome,
                resultCode, startedAt, completedAt, expiresAt
            ) VALUES (
                #{eventId}, #{permission}, #{resourceType}, #{resourceId},
                #{actorType}, #{actorUserId}, #{transport}, #{outcome},
                #{resultCode}, #{startedAt}, #{completedAt}, #{expiresAt}
            )
            """)
    int insertStarted(GenerationControlAuditEntity entity);

    @Update("""
            UPDATE generation_control_audit_event
            SET outcome = #{outcome},
                resultCode = #{resultCode},
                completedAt = #{completedAt}
            WHERE eventId = #{eventId}
              AND outcome = 'STARTED'
              AND completedAt IS NULL
            """)
    int complete(@Param("eventId") String eventId,
                 @Param("outcome") String outcome,
                 @Param("resultCode") String resultCode,
                 @Param("completedAt") LocalDateTime completedAt);

    @Delete("""
            DELETE FROM generation_control_audit_event
            WHERE expiresAt <= #{now}
            ORDER BY expiresAt ASC, id ASC
            LIMIT #{limit}
            """)
    int deleteExpired(@Param("now") LocalDateTime now,
                      @Param("limit") int limit);
}
