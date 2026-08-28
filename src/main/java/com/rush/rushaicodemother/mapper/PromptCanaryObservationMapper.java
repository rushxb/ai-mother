package com.rush.rushaicodemother.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 按实际模型调用 Prompt 身份聚合 stable/canary 生产结果。 */
public interface PromptCanaryObservationMapper {

    @Select("""
            WITH attributed_task AS (
                SELECT selection.taskId,
                       MAX(CASE WHEN selection.channel = 'stable'
                                    AND selection.promptVersion = #{query.stableVersion}
                                    AND selection.contentHash = #{query.stableContentHash}
                                THEN 1 ELSE 0 END) AS stableHit,
                       MAX(CASE WHEN selection.channel = 'canary'
                                    AND selection.promptVersion = #{query.canaryVersion}
                                    AND selection.contentHash = #{query.canaryContentHash}
                                THEN 1 ELSE 0 END) AS canaryHit,
                       MAX(CASE WHEN (selection.channel = 'stable'
                                         AND selection.promptVersion = #{query.stableVersion}
                                         AND selection.contentHash = #{query.stableContentHash})
                                    OR (selection.channel = 'canary'
                                         AND selection.promptVersion = #{query.canaryVersion}
                                         AND selection.contentHash = #{query.canaryContentHash})
                                THEN 0 ELSE 1 END) AS invalidHit
                FROM generation_model_prompt_selection selection
                JOIN generation_model_call call_record
                  ON call_record.callId = selection.callId
                 AND call_record.taskId = selection.taskId
                 AND call_record.isDelete = 0
                 AND call_record.invocationPurpose = 'GENERATION'
                WHERE selection.isDelete = 0
                  AND selection.promptKey = #{query.promptKey}
                  AND selection.bundleId = #{query.bundleId}
                  AND selection.createTime >= #{query.windowStart}
                  AND selection.createTime < #{query.windowEnd}
                GROUP BY selection.taskId
            ),
            cohort_task AS (
                SELECT taskId,
                       CASE WHEN stableHit = 1 AND canaryHit = 0 AND invalidHit = 0 THEN 'stable'
                            WHEN stableHit = 0 AND canaryHit = 1 AND invalidHit = 0 THEN 'canary'
                            ELSE NULL END AS cohort
                FROM attributed_task
            ),
            feedback_by_task AS (
                SELECT taskId, MIN(rating) AS rating
                FROM generation_feedback
                WHERE isDelete = 0
                GROUP BY taskId
            ),
            model_cost AS (
                SELECT call_record.taskId,
                       COUNT(*) AS physicalCallCount,
                       SUM(CASE WHEN call_record.callStatus IN ('SUCCESS', 'ERROR')
                           THEN 1 ELSE 0 END) AS terminalCallCount,
                       SUM(CASE WHEN call_record.totalTokens IS NOT NULL
                           THEN 1 ELSE 0 END) AS costObservedCallCount,
                       COALESCE(SUM(call_record.totalTokens), 0) AS totalProviderTokens
                FROM generation_model_call call_record
                JOIN cohort_task cohort ON cohort.taskId = call_record.taskId
                WHERE cohort.cohort IS NOT NULL
                  AND call_record.isDelete = 0
                  AND call_record.invocationPurpose = 'GENERATION'
                GROUP BY call_record.taskId
            ),
            terminal_task AS (
                SELECT cohort.cohort,
                       task.taskId,
                       task.status,
                       task.requiresBuildValidation,
                       task.firstBuildPassed,
                       task.repairRounds,
                       task.firstPreviewMillis,
                       task.durationMs,
                       task.failureCategory,
                       task.creditCost,
                       feedback.rating,
                       COALESCE(model_cost.physicalCallCount, 0) AS physicalCallCount,
                       COALESCE(model_cost.terminalCallCount, 0) AS terminalCallCount,
                       COALESCE(model_cost.costObservedCallCount, 0) AS costObservedCallCount,
                       COALESCE(model_cost.totalProviderTokens, 0) AS totalProviderTokens
                FROM cohort_task cohort
                JOIN generation_task task
                  ON task.taskId = cohort.taskId AND task.isDelete = 0
                LEFT JOIN feedback_by_task feedback ON feedback.taskId = task.taskId
                LEFT JOIN model_cost ON model_cost.taskId = task.taskId
                WHERE cohort.cohort IS NOT NULL
                  AND task.status IN ('success', 'failed', 'cancelled', 'deadline_exceeded')
                  AND task.endTime >= #{query.windowStart}
                  AND task.endTime < #{query.windowEnd}
            ),
            duration_ranked AS (
                SELECT cohort, durationMs,
                       CUME_DIST() OVER (PARTITION BY cohort ORDER BY durationMs) AS percentileRank
                FROM terminal_task
                WHERE durationMs IS NOT NULL
            ),
            first_useful_ranked AS (
                SELECT cohort, firstPreviewMillis,
                       CUME_DIST() OVER (
                           PARTITION BY cohort ORDER BY firstPreviewMillis) AS percentileRank
                FROM terminal_task
                WHERE firstPreviewMillis IS NOT NULL
            ),
            duration_p95 AS (
                SELECT cohort, MIN(durationMs) AS p95DeliveredMs
                FROM duration_ranked
                WHERE percentileRank >= 0.95
                GROUP BY cohort
            ),
            first_useful_p95 AS (
                SELECT cohort, MIN(firstPreviewMillis) AS p95FirstUsefulMs
                FROM first_useful_ranked
                WHERE percentileRank >= 0.95
                GROUP BY cohort
            ),
            bucket AS (
                SELECT cohort,
                       COUNT(*) AS taskCount,
                       SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END) AS successCount,
                       SUM(CASE WHEN requiresBuildValidation = 1 THEN 1 ELSE 0 END)
                           AS validationRequiredCount,
                       SUM(CASE WHEN requiresBuildValidation = 1 AND firstBuildPassed IS NOT NULL
                           THEN 1 ELSE 0 END) AS validationObservedCount,
                       SUM(CASE WHEN requiresBuildValidation = 1 AND firstBuildPassed = 1
                           THEN 1 ELSE 0 END) AS firstBuildPassCount,
                       SUM(CASE WHEN repairRounds IS NOT NULL THEN 1 ELSE 0 END)
                           AS repairObservedCount,
                       COALESCE(SUM(repairRounds), 0) AS totalRepairRounds,
                       COUNT(rating) AS feedbackCount,
                       SUM(CASE WHEN rating <= 2 THEN 1 ELSE 0 END) AS lowRatingCount,
                       AVG(rating) AS averageRating,
                       COUNT(firstPreviewMillis) AS firstUsefulObservedCount,
                       AVG(firstPreviewMillis) AS averageFirstUsefulMs,
                       COUNT(durationMs) AS deliveredObservedCount,
                       AVG(durationMs) AS averageDeliveredMs,
                       SUM(CASE WHEN physicalCallCount = costObservedCallCount
                           THEN 1 ELSE 0 END) AS providerCostObservedCount,
                       COALESCE(SUM(totalProviderTokens), 0) AS totalProviderTokens,
                       COUNT(creditCost) AS creditCostObservedCount,
                       COALESCE(SUM(creditCost), 0) AS totalCreditCost,
                       SUM(CASE WHEN physicalCallCount = terminalCallCount
                           THEN 1 ELSE 0 END) AS capacityObservedTaskCount,
                       COALESCE(SUM(physicalCallCount), 0) AS totalPhysicalModelCalls,
                       COALESCE(MAX(physicalCallCount), 0) AS maximumPhysicalModelCallsPerTask,
                       SUM(CASE WHEN failureCategory = 'model_rate_limit'
                           THEN 1 ELSE 0 END) AS capacityFailureCount
                FROM terminal_task
                GROUP BY cohort
            )
            SELECT #{query.intentSignature} AS intentSignature,
                   'prompt-canary-v1' AS profileVersion,
                   #{query.decisionVersion} AS decisionVersion,
                   bucket.cohort AS route,
                   CASE WHEN bucket.cohort = 'stable' THEN #{query.stableReleaseIdentity}
                        ELSE #{query.canaryReleaseIdentity} END AS releaseIdentity,
                   bucket.taskCount, bucket.successCount,
                   bucket.validationRequiredCount, bucket.validationObservedCount,
                   bucket.firstBuildPassCount, bucket.repairObservedCount,
                   bucket.totalRepairRounds, bucket.feedbackCount, bucket.lowRatingCount,
                   bucket.averageRating, bucket.firstUsefulObservedCount,
                   bucket.averageFirstUsefulMs, first_useful_p95.p95FirstUsefulMs,
                   bucket.deliveredObservedCount, bucket.averageDeliveredMs,
                   duration_p95.p95DeliveredMs, bucket.providerCostObservedCount,
                   bucket.totalProviderTokens, bucket.creditCostObservedCount,
                   bucket.totalCreditCost, bucket.capacityObservedTaskCount,
                   bucket.totalPhysicalModelCalls, bucket.maximumPhysicalModelCallsPerTask,
                   bucket.capacityFailureCount
            FROM bucket
            LEFT JOIN first_useful_p95 USING (cohort)
            LEFT JOIN duration_p95 USING (cohort)
            ORDER BY bucket.cohort
            """)
    List<GenerationScenarioBucketRow> summarize(
            @Param("query") PromptCanaryObservationQuery query);

    @Select("""
            WITH attributed_task AS (
                SELECT selection.taskId,
                       MAX(CASE WHEN selection.channel = 'stable'
                                    AND selection.promptVersion = #{query.stableVersion}
                                    AND selection.contentHash = #{query.stableContentHash}
                                THEN 1 ELSE 0 END) AS stableHit,
                       MAX(CASE WHEN selection.channel = 'canary'
                                    AND selection.promptVersion = #{query.canaryVersion}
                                    AND selection.contentHash = #{query.canaryContentHash}
                                THEN 1 ELSE 0 END) AS canaryHit,
                       MAX(CASE WHEN (selection.channel = 'stable'
                                         AND selection.promptVersion = #{query.stableVersion}
                                         AND selection.contentHash = #{query.stableContentHash})
                                    OR (selection.channel = 'canary'
                                         AND selection.promptVersion = #{query.canaryVersion}
                                         AND selection.contentHash = #{query.canaryContentHash})
                                THEN 0 ELSE 1 END) AS invalidHit
                FROM generation_model_prompt_selection selection
                JOIN generation_model_call call_record
                  ON call_record.callId = selection.callId
                 AND call_record.taskId = selection.taskId
                 AND call_record.isDelete = 0
                 AND call_record.invocationPurpose = 'GENERATION'
                WHERE selection.isDelete = 0
                  AND selection.promptKey = #{query.promptKey}
                  AND selection.bundleId = #{query.bundleId}
                  AND selection.createTime >= #{query.windowStart}
                  AND selection.createTime < #{query.windowEnd}
                GROUP BY selection.taskId
            )
            SELECT COALESCE(SUM(CASE WHEN stableHit = 1 AND canaryHit = 1 AND invalidHit = 0
                       THEN 1 ELSE 0 END), 0) AS ambiguousTaskCount,
                   COALESCE(SUM(CASE WHEN invalidHit = 1 THEN 1 ELSE 0 END), 0)
                       AS invalidAttributionTaskCount
            FROM attributed_task
            """)
    PromptCanaryAttributionExclusionRow countExcluded(
            @Param("query") PromptCanaryObservationQuery query);
}
