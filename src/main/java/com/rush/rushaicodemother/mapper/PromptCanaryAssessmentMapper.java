package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.PromptCanaryAssessmentEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

/** Prompt 灰度评估只追加写入。 */
public interface PromptCanaryAssessmentMapper {

    @Insert("""
            INSERT INTO ai_prompt_canary_assessment (
                assessmentId, promptKey, releaseRevision, bundleRevision, bundleId,
                stableVersion, stableContentHash, canaryVersion, canaryContentHash,
                windowStart, windowEnd, decision, stableTaskCount, canaryTaskCount,
                ambiguousTaskCount, invalidAttributionTaskCount, violationsJson,
                evidenceJson, evidenceHash, evaluatedAt, createTime
            ) VALUES (
                #{assessmentId}, #{promptKey}, #{releaseRevision}, #{bundleRevision}, #{bundleId},
                #{stableVersion}, #{stableContentHash}, #{canaryVersion}, #{canaryContentHash},
                #{windowStart}, #{windowEnd}, #{decision}, #{stableTaskCount}, #{canaryTaskCount},
                #{ambiguousTaskCount}, #{invalidAttributionTaskCount}, #{violationsJson},
                #{evidenceJson}, #{evidenceHash}, #{evaluatedAt}, #{createTime}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(PromptCanaryAssessmentEntity entity);
}
