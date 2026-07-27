package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.model.dto.benchmark.GenerationBenchmarkEvidenceSubmitRequest;
import com.rush.rushaicodemother.model.vo.GenerationBenchmarkEvidenceVO;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceManagementService;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceRecord;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubmission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生成基准测试证据后端接口控制器。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/generation-benchmark/admin/evidence")
public class GenerationBenchmarkEvidenceController {

    private final GenerationBenchmarkEvidenceManagementService managementService;

    @PostMapping
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<GenerationBenchmarkEvidenceVO> ingest(
            @Valid @RequestBody GenerationBenchmarkEvidenceSubmitRequest request) {
        GenerationBenchmarkEvidenceRecord evidence = managementService.ingest(
                new GenerationBenchmarkEvidenceSubmission(
                        request.getSignatureVersion(),
                        request.getSubjectType(),
                        request.getSubjectKey(),
                        request.getCandidateFingerprint(),
                        request.getCandidatePhysicalRequestCount(),
                        request.getDatasetFingerprint(),
                        request.getGraderFingerprint(),
                        request.getRuntimeConfigFingerprint(),
                        request.getGitCommit(),
                        request.getModelFingerprint(),
                        request.getPromptBundleFingerprint(),
                        request.getReportJson(),
                        request.getEvaluatedAt(),
                        request.getExpiresAt(),
                        request.getSignature()
                ));
        return ResultUtils.success(toView(evidence));
    }

    @GetMapping("/{evidenceId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<GenerationBenchmarkEvidenceVO> get(
            @PathVariable
            @Pattern(regexp = "[0-9a-fA-F-]{36}") String evidenceId) {
        return ResultUtils.success(toView(managementService.get(evidenceId)));
    }

    private GenerationBenchmarkEvidenceVO toView(GenerationBenchmarkEvidenceRecord evidence) {
        var payload = evidence.payload();
        return new GenerationBenchmarkEvidenceVO(
                evidence.evidenceId(),
                payload.subjectType().name(),
                payload.subjectKey(),
                payload.candidateFingerprint(),
                payload.signatureVersion(),
                payload.candidatePhysicalRequestCount(),
                payload.datasetFingerprint(),
                payload.graderFingerprint(),
                payload.runtimeConfigFingerprint(),
                payload.gitCommit(),
                payload.modelFingerprint(),
                payload.promptBundleFingerprint(),
                evidence.passed(),
                evidence.violations(),
                payload.evaluatedAt(),
                payload.expiresAt(),
                evidence.createdAt()
        );
    }
}
