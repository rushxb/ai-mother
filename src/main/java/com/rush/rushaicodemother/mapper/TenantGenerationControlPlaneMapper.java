package com.rush.rushaicodemother.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 租户生成控制面的低敏只读聚合 SQL。 */
public interface TenantGenerationControlPlaneMapper {

    @Select("""
            SELECT COALESCE(NULLIF(task.route, ''), 'unknown') AS route,
                   COALESCE(NULLIF(task.targetCodeGenType, ''),
                            NULLIF(task.originalCodeGenType, ''),
                            'unknown') AS targetCodeGenType,
                   COUNT(*) AS settledTasks,
                   SUM(CASE WHEN task.status = 'success' THEN 1 ELSE 0 END)
                       AS successfulDeliveries,
                   COALESCE(SUM(task.creditCost), 0) AS totalCreditCost
            FROM generation_task task
            WHERE task.tenantId = #{tenantId}
              AND task.creditCharged = 1
              AND task.creditCost IS NOT NULL
              AND task.endTime >= #{periodStart}
              AND task.endTime < #{observedBefore}
              AND task.isDelete = 0
            GROUP BY COALESCE(NULLIF(task.route, ''), 'unknown'),
                     COALESCE(NULLIF(task.targetCodeGenType, ''),
                              NULLIF(task.originalCodeGenType, ''),
                              'unknown')
            HAVING SUM(CASE WHEN task.status = 'success' THEN 1 ELSE 0 END) > 0
            ORDER BY route ASC, targetCodeGenType ASC
            """)
    List<TenantGenerationScenarioCostRow> selectScenarioCosts(
            @Param("tenantId") Long tenantId,
            @Param("periodStart") LocalDateTime periodStart,
            @Param("observedBefore") LocalDateTime observedBefore);

    @Select("""
            SELECT COALESCE(SUM(CASE WHEN status = 'queued' THEN 1 ELSE 0 END), 0)
                       AS queuedTasks,
                   COALESCE(SUM(CASE WHEN status = 'running' THEN 1 ELSE 0 END), 0)
                       AS runningTasks,
                   COALESCE(SUM(CASE WHEN status = 'waiting_approval' THEN 1 ELSE 0 END), 0)
                       AS waitingApprovalTasks,
                   COALESCE(SUM(CASE WHEN status IN ('queued', 'running', 'waiting_approval')
                                     THEN 1 ELSE 0 END), 0) AS totalNonTerminalTasks,
                   COALESCE(SUM(CASE WHEN route = 'heavy_generation'
                                          AND status IN ('queued', 'running', 'waiting_approval')
                                     THEN 1 ELSE 0 END), 0) AS heavyNonTerminalTasks
            FROM generation_task
            WHERE tenantId = #{tenantId}
              AND isDelete = 0
            """)
    TenantGenerationQueueRow selectQueueSummary(@Param("tenantId") Long tenantId);
}
