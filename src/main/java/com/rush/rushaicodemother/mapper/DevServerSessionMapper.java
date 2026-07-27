package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.DevServerSessionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 开发服务器会话数据访问映射器。
 */
public interface DevServerSessionMapper {

    @Select("SELECT id FROM user WHERE id = #{userId} FOR UPDATE")
    Long lockUser(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM dev_server_session
            WHERE userId = #{userId}
              AND state IN ('STARTING', 'RUNNING', 'STOPPING', 'RECOVERING')
              AND leaseUntil IS NOT NULL
              AND leaseUntil >= #{now}
            """)
    long countActiveByUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO dev_server_session (
                appId, userId, nodeId, leaseOwner, state, port, projectDirectory,
                sandboxBackend, cleanupResourceIds, leaseUntil, heartbeatAt,
                version, lastError, createTime, updateTime
            ) VALUES (
                #{appId}, #{userId}, #{nodeId}, #{leaseOwner}, #{state}, #{port}, #{projectDirectory},
                #{sandboxBackend}, #{cleanupResourceIds}, #{leaseUntil}, #{heartbeatAt},
                #{version}, #{lastError}, #{createTime}, #{updateTime}
            )
            """)
    int insert(DevServerSessionEntity entity);

    @Update("""
            UPDATE dev_server_session
            SET userId = #{userId}, nodeId = #{nodeId}, leaseOwner = #{leaseOwner},
                state = 'STARTING', port = #{port}, projectDirectory = #{projectDirectory},
                sandboxBackend = NULL, cleanupResourceIds = NULL,
                leaseUntil = #{leaseUntil}, heartbeatAt = #{now},
                version = version + 1, lastError = NULL, updateTime = #{now}
            WHERE appId = #{appId}
              AND state IN ('STOPPED', 'FAILED')
            """)
    int claimTerminal(@Param("appId") Long appId,
                      @Param("userId") Long userId,
                      @Param("nodeId") String nodeId,
                      @Param("leaseOwner") String leaseOwner,
                      @Param("port") int port,
                      @Param("projectDirectory") String projectDirectory,
                      @Param("now") LocalDateTime now,
                      @Param("leaseUntil") LocalDateTime leaseUntil);

    @Select("""
            SELECT appId, userId, nodeId, leaseOwner, state, port, projectDirectory,
                   sandboxBackend, cleanupResourceIds, leaseUntil, heartbeatAt,
                   version, lastError, createTime, updateTime
            FROM dev_server_session
            WHERE appId = #{appId}
            LIMIT 1
            """)
    DevServerSessionEntity selectByAppId(@Param("appId") Long appId);

    @Update("""
            UPDATE dev_server_session
            SET sandboxBackend = #{sandboxBackend}, cleanupResourceIds = #{cleanupResourceIds},
                heartbeatAt = #{now}, leaseUntil = #{leaseUntil},
                version = version + 1, updateTime = #{now}
            WHERE appId = #{appId}
              AND leaseOwner = #{leaseOwner}
              AND state = 'STARTING'
              AND leaseUntil >= #{now}
            """)
    int recordStartingResources(@Param("appId") Long appId,
                                @Param("leaseOwner") String leaseOwner,
                                @Param("sandboxBackend") String sandboxBackend,
                                @Param("cleanupResourceIds") String cleanupResourceIds,
                                @Param("now") LocalDateTime now,
                                @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE dev_server_session
            SET state = 'RUNNING', sandboxBackend = #{sandboxBackend},
                cleanupResourceIds = #{cleanupResourceIds}, heartbeatAt = #{now},
                leaseUntil = #{leaseUntil}, version = version + 1, updateTime = #{now}
            WHERE appId = #{appId}
              AND leaseOwner = #{leaseOwner}
              AND state = 'STARTING'
              AND leaseUntil >= #{now}
            """)
    int markRunning(@Param("appId") Long appId,
                    @Param("leaseOwner") String leaseOwner,
                    @Param("sandboxBackend") String sandboxBackend,
                    @Param("cleanupResourceIds") String cleanupResourceIds,
                    @Param("now") LocalDateTime now,
                    @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE dev_server_session
            SET heartbeatAt = #{now}, leaseUntil = #{leaseUntil},
                version = version + 1, updateTime = #{now}
            WHERE appId = #{appId}
              AND leaseOwner = #{leaseOwner}
              AND state IN ('STARTING', 'RUNNING')
              AND leaseUntil >= #{now}
            """)
    int renew(@Param("appId") Long appId,
              @Param("leaseOwner") String leaseOwner,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE dev_server_session
            SET state = 'STOPPING', version = version + 1, updateTime = #{requestedAt}
            WHERE appId = #{appId}
              AND state IN ('STARTING', 'RUNNING', 'STOPPING')
            """)
    int requestStop(@Param("appId") Long appId,
                    @Param("requestedAt") LocalDateTime requestedAt);

    @Update("""
            UPDATE dev_server_session
            SET state = 'STOPPING', heartbeatAt = #{now}, leaseUntil = #{leaseUntil},
                version = version + 1, updateTime = #{now}
            WHERE appId = #{appId}
              AND leaseOwner = #{leaseOwner}
              AND state IN ('STARTING', 'RUNNING', 'STOPPING')
              AND leaseUntil >= #{now}
            """)
    int markStopping(@Param("appId") Long appId,
                     @Param("leaseOwner") String leaseOwner,
                     @Param("now") LocalDateTime now,
                     @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE dev_server_session
            SET state = 'STOPPED', leaseOwner = NULL, leaseUntil = NULL,
                heartbeatAt = #{stoppedAt}, version = version + 1,
                lastError = #{reason}, updateTime = #{stoppedAt}
            WHERE appId = #{appId}
              AND leaseOwner = #{leaseOwner}
              AND state IN ('STARTING', 'RUNNING', 'STOPPING', 'RECOVERING')
            """)
    int markStopped(@Param("appId") Long appId,
                    @Param("leaseOwner") String leaseOwner,
                    @Param("stoppedAt") LocalDateTime stoppedAt,
                    @Param("reason") String reason);

    @Select("""
            SELECT appId, userId, nodeId, leaseOwner, state, port, projectDirectory,
                   sandboxBackend, cleanupResourceIds, leaseUntil, heartbeatAt,
                   version, lastError, createTime, updateTime
            FROM dev_server_session
            WHERE state IN ('STARTING', 'RUNNING', 'STOPPING', 'RECOVERING')
              AND (leaseUntil IS NULL OR leaseUntil < #{now})
            ORDER BY COALESCE(leaseUntil, updateTime) ASC, appId ASC
            LIMIT #{limit}
            """)
    List<DevServerSessionEntity> selectExpired(@Param("now") LocalDateTime now,
                                               @Param("limit") int limit);

    @Update("""
            UPDATE dev_server_session
            SET nodeId = #{nodeId}, leaseOwner = #{recoveryOwner}, state = 'RECOVERING',
                heartbeatAt = #{now}, leaseUntil = #{leaseUntil},
                version = version + 1, updateTime = #{now}
            WHERE appId = #{appId}
              AND version = #{version}
              AND state IN ('STARTING', 'RUNNING', 'STOPPING', 'RECOVERING')
              AND (leaseUntil IS NULL OR leaseUntil < #{now})
            """)
    int claimRecovery(@Param("appId") Long appId,
                      @Param("version") long version,
                      @Param("nodeId") String nodeId,
                      @Param("recoveryOwner") String recoveryOwner,
                      @Param("now") LocalDateTime now,
                      @Param("leaseUntil") LocalDateTime leaseUntil);
}
