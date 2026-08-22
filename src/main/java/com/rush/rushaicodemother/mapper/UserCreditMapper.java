package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.dto.credit.ProviderCostObservationRow;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.entity.UserCreditTransaction;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 用户积分账务的显式 SQL Mapper。 */
public interface UserCreditMapper {

    @Select("""
            SELECT id, creditBalance
            FROM `user`
            WHERE id = #{userId}
              AND isDelete = 0
            """)
    User selectActiveCreditAccount(@Param("userId") Long userId);

    @Select("""
            SELECT id, creditBalance
            FROM `user`
            WHERE id = #{userId}
              AND isDelete = 0
            FOR UPDATE
            """)
    User selectActiveCreditAccountForUpdate(@Param("userId") Long userId);

    @Select("""
            SELECT id, taskId, userId, tenantId, creditCharged
            FROM generation_task
            WHERE taskId = #{taskId}
              AND isDelete = 0
            FOR UPDATE
            """)
    GenerationTask selectGenerationTaskForUpdate(@Param("taskId") String taskId);

    @Select("""
            SELECT id, taskId, userId, tenantId, creditCharged
            FROM generation_task
            WHERE taskId = #{taskId}
              AND isDelete = 0
            LIMIT 1
            """)
    GenerationTask selectGenerationTask(@Param("taskId") String taskId);

    @Select("""
            SELECT id, userId, tenantId, changeAmount, balanceAfter, type, bizId, remark,
                   adminUserId, tokenCount, createTime, isDelete
            FROM user_credit_transaction
            WHERE type = #{type}
              AND bizId = #{bizId}
              AND isDelete = 0
            LIMIT 1
            """)
    UserCreditTransaction selectTransactionByTypeAndBizId(@Param("type") String type,
                                                           @Param("bizId") String bizId);

    /**
     * 按物理 attempt 聚合 Provider 成本事实，不在 SQL 中混入用户收费策略。
     * ERROR 的稳定错误分类兼容现有账本，并显式恢复 CANCEL/TIMEOUT 语义。
     */
    @Select("""
            SELECT COALESCE(SUM(
                       CASE WHEN callStatus = 'SUCCESS' AND totalTokens > 0
                            THEN totalTokens ELSE 0 END), 0) AS successfulTokens,
                   COALESCE(SUM(
                       CASE WHEN callStatus = 'ERROR'
                                  AND errorCategory = 'model_cancelled'
                                  AND totalTokens > 0
                            THEN totalTokens ELSE 0 END), 0) AS cancelledTokens,
                   COALESCE(SUM(
                       CASE WHEN callStatus = 'ERROR'
                                  AND errorCategory = 'model_timeout'
                                  AND totalTokens > 0
                            THEN totalTokens ELSE 0 END), 0) AS timedOutTokens,
                   COALESCE(SUM(
                       CASE WHEN callStatus = 'ERROR'
                                  AND (errorCategory IS NULL OR errorCategory NOT IN (
                                      'model_cancelled', 'model_timeout'))
                                  AND totalTokens > 0
                            THEN totalTokens ELSE 0 END), 0) AS failedTokens,
                   COALESCE(SUM(
                       CASE WHEN callStatus = 'STARTED'
                                  OR usageSource = 'UNAVAILABLE'
                                  OR totalTokens IS NULL
                                  OR totalTokens <= 0
                            THEN 1 ELSE 0 END), 0) AS pendingAttemptCount
            FROM generation_model_call
            WHERE taskId = #{taskId}
              AND invocationPurpose = 'GENERATION'
              AND billingMode = 'BILLABLE'
              AND isDelete = 0
            """)
    ProviderCostObservationRow selectTaskProviderCostObservation(
            @Param("taskId") String taskId);

    @Update("""
            UPDATE `user`
            SET creditBalance = #{creditBalance}
            WHERE id = #{userId}
              AND isDelete = 0
            """)
    int updateCreditBalance(@Param("userId") Long userId,
                            @Param("creditBalance") Long creditBalance);

    @Insert("""
            INSERT INTO user_credit_transaction (
                userId, tenantId, changeAmount, balanceAfter, type, bizId, remark, adminUserId, tokenCount
            ) VALUES (
                #{userId}, #{tenantId}, #{changeAmount}, #{balanceAfter}, #{type}, #{bizId}, #{remark},
                #{adminUserId}, #{tokenCount}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertTransaction(UserCreditTransaction transaction);

    @Update("""
            UPDATE generation_task
            SET creditCharged = 1,
                creditCost = #{creditCost},
                totalTokens = #{totalTokens}
            WHERE id = #{taskRecordId}
              AND isDelete = 0
              AND creditCharged = 0
            """)
    int updateCreditSettlement(@Param("taskRecordId") Long taskRecordId,
                               @Param("creditCost") Long creditCost,
                               @Param("totalTokens") Long totalTokens);

    @Select("""
            SELECT taskId
            FROM generation_task
            WHERE status IN ('success', 'failed', 'cancelled', 'deadline_exceeded')
              AND creditCharged = 0
              AND isDelete = 0
            ORDER BY endTime ASC, id ASC
            LIMIT #{limit}
            """)
    List<String> selectUnsettledTerminalTaskIds(@Param("limit") int limit);

    /**
     * 扫描没有正式任务和结算流水的过期预检预授权。
     * remark 前缀仅承担流水阶段标识，不参与金额或身份推导。
     */
    @Select("""
            SELECT reservation.bizId
            FROM user_credit_transaction reservation
            LEFT JOIN generation_task task
              ON task.taskId = reservation.bizId
             AND task.isDelete = 0
            LEFT JOIN user_credit_transaction settlement
              ON settlement.type = 'GENERATION_SETTLEMENT'
             AND settlement.bizId = reservation.bizId
             AND settlement.isDelete = 0
            WHERE reservation.type = 'GENERATION_RESERVATION'
              AND reservation.remark LIKE 'reservation:preflight:%'
              AND reservation.createTime <= #{createdBefore}
              AND reservation.isDelete = 0
              AND task.id IS NULL
              AND settlement.id IS NULL
            ORDER BY reservation.createTime ASC, reservation.id ASC
            LIMIT #{limit}
            """)
    List<String> selectRecoverablePreflightReservationTaskIds(
            @Param("createdBefore") LocalDateTime createdBefore,
            @Param("limit") int limit);
}
