package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.entity.UserCreditTransaction;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
            SELECT id, taskId, userId, creditCharged
            FROM generation_task
            WHERE taskId = #{taskId}
              AND isDelete = 0
            FOR UPDATE
            """)
    GenerationTask selectGenerationTaskForUpdate(@Param("taskId") String taskId);

    @Select("""
            SELECT id, userId, changeAmount, balanceAfter, type, bizId, remark,
                   adminUserId, tokenCount, createTime, isDelete
            FROM user_credit_transaction
            WHERE type = #{type}
              AND bizId = #{bizId}
              AND isDelete = 0
            LIMIT 1
            """)
    UserCreditTransaction selectTransactionByTypeAndBizId(@Param("type") String type,
                                                           @Param("bizId") String bizId);

    @Select("""
            SELECT COALESCE(SUM(CASE WHEN totalTokens > 0 THEN totalTokens ELSE 0 END), 0)
            FROM generation_model_call
            WHERE taskId = #{taskId}
              AND isDelete = 0
            """)
    Long sumPositiveTaskTokens(@Param("taskId") String taskId);

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
                userId, changeAmount, balanceAfter, type, bizId, remark, adminUserId, tokenCount
            ) VALUES (
                #{userId}, #{changeAmount}, #{balanceAfter}, #{type}, #{bizId}, #{remark},
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
}
