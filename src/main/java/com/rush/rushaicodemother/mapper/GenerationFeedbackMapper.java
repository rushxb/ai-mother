package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationFeedback;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackSummary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GenerationFeedbackMapper {

    @Insert("""
            INSERT INTO generation_feedback (
                taskId, appId, userId, rating, outcome, comment,
                createTime, updateTime, isDelete
            ) VALUES (
                #{taskId}, #{appId}, #{userId}, #{rating}, #{outcome}, #{comment},
                #{createTime}, #{updateTime}, 0
            )
            ON DUPLICATE KEY UPDATE
                rating = VALUES(rating),
                outcome = VALUES(outcome),
                comment = VALUES(comment),
                updateTime = VALUES(updateTime),
                isDelete = 0
            """)
    int upsert(GenerationFeedback feedback);

    @Select("""
            SELECT id, taskId, appId, userId, rating, outcome, comment, createTime, updateTime, isDelete
            FROM generation_feedback
            WHERE taskId = #{taskId}
              AND userId = #{userId}
              AND isDelete = 0
            LIMIT 1
            """)
    GenerationFeedback selectByTaskIdAndUserId(@Param("taskId") String taskId,
                                               @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) AS feedbackCount,
                   COALESCE(SUM(CASE WHEN rating <= 2 THEN 1 ELSE 0 END), 0) AS lowRatingCount,
                   COALESCE(AVG(rating), 0) AS averageRating
            FROM generation_feedback
            WHERE appId = #{appId}
              AND isDelete = 0
            """)
    GenerationFeedbackSummary summarizeByAppId(@Param("appId") Long appId);
}
