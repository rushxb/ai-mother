package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioAttribution;
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
            WITH scenario_task AS (
                SELECT task.intentSignature,
                       task.intentProfileVersion AS profileVersion,
                       task.routeDecisionVersion AS decisionVersion,
                       task.route,
                       task.routeReleaseIdentity AS releaseIdentity,
                       task.status,
                       task.requiresBuildValidation,
                       task.firstBuildPassed,
                       task.repairRounds,
                       task.firstPreviewMillis,
                       task.durationMs,
                       task.failureCategory,
                       COALESCE(model_cost.physicalCallCount, 0) AS physicalCallCount,
                       COALESCE(model_cost.terminalCallCount, 0) AS terminalCallCount,
                       COALESCE(model_cost.costObservedCallCount, 0) AS costObservedCallCount,
                       COALESCE(model_cost.totalProviderTokens, 0) AS totalProviderTokens,
                       task.creditCost,
                       feedback.rating
                FROM generation_task task
                LEFT JOIN generation_feedback feedback
                  ON feedback.taskId = task.taskId AND feedback.isDelete = 0
                LEFT JOIN (
                    SELECT taskId,
                           COUNT(*) AS physicalCallCount,
                           SUM(CASE WHEN callStatus IN ('SUCCESS', 'ERROR')
                               THEN 1 ELSE 0 END) AS terminalCallCount,
                           SUM(CASE WHEN totalTokens IS NOT NULL THEN 1 ELSE 0 END) AS costObservedCallCount,
                           COALESCE(SUM(totalTokens), 0) AS totalProviderTokens
                    FROM generation_model_call
                    WHERE isDelete = 0
                      AND invocationPurpose = 'GENERATION'
                    GROUP BY taskId
                ) model_cost ON model_cost.taskId = task.taskId
                WHERE task.isDelete = 0
                  AND task.intentSignature = #{intentSignature}
                  AND task.endTime >= #{from}
                  AND task.endTime < #{to}
            ),
            duration_ranked AS (
                SELECT intentSignature, profileVersion, decisionVersion, route, releaseIdentity,
                       durationMs,
                       CUME_DIST() OVER (
                           PARTITION BY intentSignature, profileVersion, decisionVersion, route, releaseIdentity
                           ORDER BY durationMs
                       ) AS percentileRank
                FROM scenario_task
                WHERE durationMs IS NOT NULL
            ),
            first_useful_ranked AS (
                SELECT intentSignature, profileVersion, decisionVersion, route, releaseIdentity,
                       firstPreviewMillis,
                       CUME_DIST() OVER (
                           PARTITION BY intentSignature, profileVersion, decisionVersion, route, releaseIdentity
                           ORDER BY firstPreviewMillis
                       ) AS percentileRank
                FROM scenario_task
                WHERE firstPreviewMillis IS NOT NULL
            ),
            duration_p95 AS (
                SELECT intentSignature, profileVersion, decisionVersion, route, releaseIdentity,
                       MIN(durationMs) AS p95DeliveredMs
                FROM duration_ranked
                WHERE percentileRank >= 0.95
                GROUP BY intentSignature, profileVersion, decisionVersion, route, releaseIdentity
            ),
            first_useful_p95 AS (
                SELECT intentSignature, profileVersion, decisionVersion, route, releaseIdentity,
                       MIN(firstPreviewMillis) AS p95FirstUsefulMs
                FROM first_useful_ranked
                WHERE percentileRank >= 0.95
                GROUP BY intentSignature, profileVersion, decisionVersion, route, releaseIdentity
            ),
            bucket AS (
                SELECT intentSignature, profileVersion, decisionVersion, route, releaseIdentity,
                       COUNT(*) AS taskCount,
                       SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END) AS successCount,
                       SUM(CASE WHEN requiresBuildValidation = 1 THEN 1 ELSE 0 END)
                           AS validationRequiredCount,
                       SUM(CASE WHEN requiresBuildValidation = 1 AND firstBuildPassed IS NOT NULL
                           THEN 1 ELSE 0 END) AS validationObservedCount,
                       SUM(CASE WHEN requiresBuildValidation = 1 AND firstBuildPassed = 1
                           THEN 1 ELSE 0 END) AS firstBuildPassCount,
                       SUM(CASE WHEN repairRounds IS NOT NULL THEN 1 ELSE 0 END) AS repairObservedCount,
                       COALESCE(SUM(repairRounds), 0) AS totalRepairRounds,
                       COUNT(rating) AS feedbackCount,
                       SUM(CASE WHEN rating <= 2 THEN 1 ELSE 0 END) AS lowRatingCount,
                       AVG(rating) AS averageRating,
                       COUNT(firstPreviewMillis) AS firstUsefulObservedCount,
                       AVG(firstPreviewMillis) AS averageFirstUsefulMs,
                       COUNT(durationMs) AS deliveredObservedCount,
                       AVG(durationMs) AS averageDeliveredMs,
                       SUM(CASE WHEN COALESCE(physicalCallCount, 0)
                           = COALESCE(costObservedCallCount, 0) THEN 1 ELSE 0 END)
                           AS providerCostObservedCount,
                       COALESCE(SUM(totalProviderTokens), 0) AS totalProviderTokens,
                       COUNT(creditCost) AS creditCostObservedCount,
                       COALESCE(SUM(creditCost), 0) AS totalCreditCost,
                       SUM(CASE WHEN COALESCE(physicalCallCount, 0)
                           = COALESCE(terminalCallCount, 0) THEN 1 ELSE 0 END)
                           AS capacityObservedTaskCount,
                       COALESCE(SUM(physicalCallCount), 0) AS totalPhysicalModelCalls,
                       COALESCE(MAX(physicalCallCount), 0) AS maximumPhysicalModelCallsPerTask,
                       SUM(CASE WHEN failureCategory = 'model_rate_limit'
                           THEN 1 ELSE 0 END) AS capacityFailureCount
                FROM scenario_task
                GROUP BY intentSignature, profileVersion, decisionVersion, route, releaseIdentity
            )
            SELECT bucket.intentSignature, bucket.profileVersion, bucket.decisionVersion,
                   bucket.route, bucket.releaseIdentity, bucket.taskCount, bucket.successCount,
                   bucket.validationRequiredCount, bucket.validationObservedCount,
                   bucket.firstBuildPassCount, bucket.repairObservedCount, bucket.totalRepairRounds,
                   bucket.feedbackCount, bucket.lowRatingCount, bucket.averageRating,
                   bucket.firstUsefulObservedCount, bucket.averageFirstUsefulMs,
                   first_useful_p95.p95FirstUsefulMs,
                   bucket.deliveredObservedCount, bucket.averageDeliveredMs,
                   duration_p95.p95DeliveredMs,
                   bucket.providerCostObservedCount, bucket.totalProviderTokens,
                   bucket.creditCostObservedCount, bucket.totalCreditCost,
                   bucket.capacityObservedTaskCount, bucket.totalPhysicalModelCalls,
                   bucket.maximumPhysicalModelCallsPerTask, bucket.capacityFailureCount
            FROM bucket
            LEFT JOIN first_useful_p95 USING
                (intentSignature, profileVersion, decisionVersion, route, releaseIdentity)
            LEFT JOIN duration_p95 USING
                (intentSignature, profileVersion, decisionVersion, route, releaseIdentity)
            ORDER BY bucket.taskCount DESC, bucket.decisionVersion,
                     bucket.releaseIdentity, bucket.route
            LIMIT #{limit}
            """)
    List<GenerationScenarioBucketRow> summarize(
            @Param("intentSignature") String intentSignature,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("limit") int limit);
}
