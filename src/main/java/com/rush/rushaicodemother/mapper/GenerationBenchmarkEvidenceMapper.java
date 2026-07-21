package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationBenchmarkEvidenceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GenerationBenchmarkEvidenceMapper {

    @Insert("""
            INSERT INTO generation_benchmark_evidence (
                evidenceId, subjectType, subjectKey, candidateFingerprint,
                datasetFingerprint, graderFingerprint, runtimeConfigFingerprint,
                gitCommit, modelFingerprint, promptBundleFingerprint,
                reportSha256, reportJson, passed, violationsJson, signature,
                evaluatedAt, expiresAt, createTime, isDelete
            ) VALUES (
                #{evidenceId}, #{subjectType}, #{subjectKey}, #{candidateFingerprint},
                #{datasetFingerprint}, #{graderFingerprint}, #{runtimeConfigFingerprint},
                #{gitCommit}, #{modelFingerprint}, #{promptBundleFingerprint},
                #{reportSha256}, #{reportJson}, #{passed}, #{violationsJson}, #{signature},
                #{evaluatedAt}, #{expiresAt}, #{createTime}, 0
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertEvidence(GenerationBenchmarkEvidenceEntity entity);

    @Select("""
            SELECT id, evidenceId, subjectType, subjectKey, candidateFingerprint,
                   datasetFingerprint, graderFingerprint, runtimeConfigFingerprint,
                   gitCommit, modelFingerprint, promptBundleFingerprint,
                   reportSha256, reportJson, passed, violationsJson, signature,
                   evaluatedAt, expiresAt, createTime, isDelete
            FROM generation_benchmark_evidence
            WHERE evidenceId = #{evidenceId}
              AND isDelete = 0
            LIMIT 1
            """)
    GenerationBenchmarkEvidenceEntity selectByEvidenceId(@Param("evidenceId") String evidenceId);
}
