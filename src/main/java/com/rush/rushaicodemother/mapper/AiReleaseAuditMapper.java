package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.AiReleaseAuditEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

/**
 * AI 发布审计数据访问映射器。
 */
public interface AiReleaseAuditMapper {

    @Insert("""
            INSERT INTO ai_release_audit (
                auditId, evidenceId, subjectType, subjectKey, candidateFingerprint,
                action, operatorUserId, releaseReference, createTime
            ) VALUES (
                #{auditId}, #{evidenceId}, #{subjectType}, #{subjectKey}, #{candidateFingerprint},
                #{action}, #{operatorUserId}, #{releaseReference}, #{createTime}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertAudit(AiReleaseAuditEntity entity);
}
