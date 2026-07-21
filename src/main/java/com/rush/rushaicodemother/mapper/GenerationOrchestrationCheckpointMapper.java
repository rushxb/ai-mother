package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationOrchestrationCheckpoint;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis SQL adapter for durable DAG checkpoints. */
public interface GenerationOrchestrationCheckpointMapper {

    @Insert("""
            INSERT INTO generation_orchestration_checkpoint (
                taskId, appId, executionEpoch, requestHash, status, runtimeState,
                currentNode, lastCompletedNode, checkpointVersion,
                payloadJson, payloadBytes, createTime, updateTime, isDelete
            ) SELECT
                #{taskId}, #{appId}, #{executionEpoch}, #{requestHash}, #{status}, #{runtimeState},
                #{currentNode}, #{lastCompletedNode}, #{checkpointVersion},
                #{payloadJson}, #{payloadBytes}, #{createTime}, #{updateTime}, 0
            WHERE #{executionEpoch} = 0
               OR EXISTS (
                    SELECT 1
                    FROM generation_task task
                    WHERE task.taskId = #{taskId}
                      AND task.executionEpoch = #{executionEpoch}
                      AND task.status IN ('queued', 'running')
                      AND task.leaseUntil >= #{updateTime}
                      AND task.isDelete = 0
               )
            """)
    int insertCheckpoint(GenerationOrchestrationCheckpoint checkpoint);

    @Update("""
            UPDATE generation_orchestration_checkpoint
            SET appId = #{appId},
                executionEpoch = #{executionEpoch},
                requestHash = #{requestHash},
                status = #{status},
                runtimeState = #{runtimeState},
                currentNode = #{currentNode},
                lastCompletedNode = #{lastCompletedNode},
                checkpointVersion = #{checkpointVersion},
                payloadJson = #{payloadJson},
                payloadBytes = #{payloadBytes},
                updateTime = #{updateTime},
                isDelete = 0
            WHERE taskId = #{taskId}
              AND (
                    executionEpoch < #{executionEpoch}
                    OR (executionEpoch = #{executionEpoch}
                        AND (isDelete = 1 OR checkpointVersion <= #{checkpointVersion}))
              )
              AND (
                    #{executionEpoch} = 0
                    OR EXISTS (
                        SELECT 1
                        FROM generation_task task
                        WHERE task.taskId = #{taskId}
                          AND task.executionEpoch = #{executionEpoch}
                          AND task.status IN ('queued', 'running')
                          AND task.leaseUntil >= #{updateTime}
                          AND task.isDelete = 0
                    )
              )
            """)
    int updateCheckpointIfNotStale(GenerationOrchestrationCheckpoint checkpoint);

    @Select("""
            SELECT payloadJson
            FROM generation_orchestration_checkpoint
            WHERE appId = #{appId}
              AND taskId = #{taskId}
              AND isDelete = 0
            LIMIT 1
            """)
    String selectPayload(@Param("appId") Long appId, @Param("taskId") String taskId);

    @Update("""
            UPDATE generation_orchestration_checkpoint
            SET isDelete = 1, updateTime = CURRENT_TIMESTAMP(6)
            WHERE appId = #{appId}
              AND taskId = #{taskId}
              AND executionEpoch = #{executionEpoch}
              AND isDelete = 0
            """)
    int softDelete(@Param("appId") Long appId,
                   @Param("taskId") String taskId,
                   @Param("executionEpoch") long executionEpoch);
}
