package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioAttribution;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioBucketSummary;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 场景归因查询 SQL；所有成本与 provenance 均从既有事实表聚合。 */
public interface GenerationScenarioAttributionMapper {

    @Select("""
            SELECT task.taskId, task.appId, task.intentSignature, task.intentProfileVersion,
                   task.routeDecisionVersion, task.route, task.routeReleaseIdentity,
                   task.status, feedback.rating, feedback.outcome AS feedbackOutcome,
                   task.changedFileCount, task.firstBuildPassed, task.repairRounds,
                   task.firstPreviewMillis, task.durationMs, task.totalTokens, task.creditCost,
                   COALESCE(calls.modelCallCount, 0) AS modelCallCount,
                   COALESCE(calls.modelErrorCount, 0) AS modelErrorCount,
                   calls.providers, calls.models, calls.promptTemplateFingerprints,
                   calls.toolSchemaFingerprints, calls.modelConfigFingerprints,
                   task.endTime AS completedAt
            FROM generation_task task
            LEFT JOIN generation_feedback feedback
              ON feedback.taskId = task.taskId AND feedback.isDelete = 0
            LEFT JOIN (
                SELECT taskId,
                       COUNT(*) AS modelCallCount,
                       SUM(CASE WHEN callStatus = 'ERROR' THEN 1 ELSE 0 END) AS modelErrorCount,
                       GROUP_CONCAT(DISTINCT provider ORDER BY provider SEPARATOR ',') AS providers,
                       GROUP_CONCAT(DISTINCT model ORDER BY model SEPARATOR ',') AS models,
                       GROUP_CONCAT(DISTINCT promptTemplateHash ORDER BY promptTemplateHash SEPARATOR ',')
                           AS promptTemplateFingerprints,
                       GROUP_CONCAT(DISTINCT toolSchemaHash ORDER BY toolSchemaHash SEPARATOR ',')
                           AS toolSchemaFingerprints,
                       GROUP_CONCAT(DISTINCT modelConfigHash ORDER BY modelConfigHash SEPARATOR ',')
                           AS modelConfigFingerprints
                FROM generation_model_call
                WHERE taskId = #{taskId}
                  AND isDelete = 0
                GROUP BY taskId
            ) calls ON calls.taskId = task.taskId
            WHERE task.taskId = #{taskId}
              AND task.isDelete = 0
            LIMIT 1
            """)
    GenerationScenarioAttribution selectByTaskId(@Param("taskId") String taskId);

    @Select("""
            SELECT task.intentSignature,
                   task.intentProfileVersion AS profileVersion,
                   task.routeDecisionVersion AS decisionVersion,
                   task.route,
                   task.routeReleaseIdentity AS releaseIdentity,
                   COUNT(*) AS taskCount,
                   SUM(CASE WHEN task.status = 'success' THEN 1 ELSE 0 END) AS successCount,
                   SUM(CASE WHEN feedback.rating <= 2 THEN 1 ELSE 0 END) AS lowRatingCount,
                   AVG(feedback.rating) AS averageRating,
                   AVG(task.durationMs) AS averageDurationMs,
                   COALESCE(SUM(task.totalTokens), 0) AS totalTokens,
                   COALESCE(SUM(task.creditCost), 0) AS totalCreditCost
            FROM generation_task task
            LEFT JOIN generation_feedback feedback
              ON feedback.taskId = task.taskId AND feedback.isDelete = 0
            WHERE task.isDelete = 0
              AND task.intentSignature = #{intentSignature}
              AND task.endTime >= #{from}
              AND task.endTime < #{to}
            GROUP BY task.intentSignature, task.intentProfileVersion,
                     task.routeDecisionVersion, task.route, task.routeReleaseIdentity
            ORDER BY taskCount DESC, task.routeDecisionVersion,
                     task.routeReleaseIdentity, task.route
            LIMIT #{limit}
            """)
    List<GenerationScenarioBucketSummary> summarize(
            @Param("intentSignature") String intentSignature,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("limit") int limit);
}
