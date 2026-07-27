package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkWorkerProperties;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReleaseAssessment;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkService;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidateIdentity;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidateIdentityResolver;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceEnvelopeRequest;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceEnvelopeService;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceManagementService;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceRecord;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubmission;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 执行候选装载、完整门禁、签名、验签入库和结果输出。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.generation-benchmark.worker",
        name = "enabled",
        havingValue = "true")
public class GenerationBenchmarkWorkerExecutionService {

    private final GenerationBenchmarkWorkerProperties properties;
    private final GenerationBenchmarkBrowserProperties browserProperties;
    private final GenerationBenchmarkBackendProperties backendProperties;
    private final GenerationBenchmarkWorkerCandidateProvider candidateProvider;
    private final GenerationBenchmarkCandidateRuntime candidateRuntime;
    private final GenerationBenchmarkCandidateInvocationTracker invocationTracker;
    private final GenerationBenchmarkEvidenceCandidateIdentityResolver identityResolver;
    private final GenerationBenchmarkService benchmarkService;
    private final GenerationBenchmarkEvidenceEnvelopeService envelopeService;
    private final GenerationBenchmarkEvidenceManagementService managementService;
    private final GenerationBenchmarkWorkerResultWriter resultWriter;

    public GenerationBenchmarkWorkerResult execute() {
        requireCompleteRuntimeGrading();
        resultWriter.prepare();
        GenerationBenchmarkEvidenceCandidate candidate = candidateProvider.candidate();
        GenerationBenchmarkEvidenceCandidateIdentity expectedBefore =
                identityResolver.resolve(candidate);
        candidateRuntime.prepare(candidate);

        GenerationBenchmarkReleaseAssessment assessment;
        GenerationBenchmarkReport report;
        long candidatePhysicalRequestCount;
        invocationTracker.begin(candidate);
        try {
            assessment = benchmarkService.runReleaseGate();
            report = assessment.report();
            candidatePhysicalRequestCount =
                    invocationTracker.requireCandidateInvoked(candidate);
        } finally {
            invocationTracker.end();
        }
        GenerationBenchmarkEvidenceCandidateIdentity expectedAfter =
                identityResolver.resolve(candidate);
        requireStableExecutionIdentity(expectedBefore, expectedAfter, report);

        if (!assessment.passed()) {
            GenerationBenchmarkWorkerResult rejected = result(
                    GenerationBenchmarkWorkerResult.Status.REJECTED,
                    expectedBefore,
                    candidatePhysicalRequestCount,
                    "",
                    assessment.violations(),
                    report
            );
            resultWriter.write(rejected);
            throw new GenerationBenchmarkWorkerRejectedException(assessment.violations());
        }

        GenerationBenchmarkEvidenceSubmission submission = envelopeService.create(
                new GenerationBenchmarkEvidenceEnvelopeRequest(
                        candidate,
                        report,
                        candidatePhysicalRequestCount,
                        properties.getEvidenceValidity()
                )
        );
        GenerationBenchmarkEvidenceRecord evidence = managementService.ingest(submission);
        if (!evidence.passed()) {
            throw new IllegalStateException("Benchmark 证据入库后未通过确定性门禁复核");
        }
        GenerationBenchmarkWorkerResult passed = result(
                GenerationBenchmarkWorkerResult.Status.PASSED,
                expectedBefore,
                candidatePhysicalRequestCount,
                evidence.evidenceId(),
                evidence.violations(),
                report
        );
        resultWriter.write(passed);
        return passed;
    }

    private void requireCompleteRuntimeGrading() {
        if (!browserProperties.isEnabled() || !backendProperties.isEnabled()) {
            throw new IllegalStateException(
                    "Benchmark Worker 必须同时启用浏览器与后端运行时评分");
        }
    }

    private void requireStableExecutionIdentity(
            GenerationBenchmarkEvidenceCandidateIdentity before,
            GenerationBenchmarkEvidenceCandidateIdentity after,
            GenerationBenchmarkReport report) {
        if (!before.equals(after)) {
            throw new IllegalStateException("Benchmark 执行期间发布候选或依赖配置发生变化");
        }
        if (!before.modelFingerprint().equals(report.modelFingerprint())
                || !before.promptBundleFingerprint().equals(report.promptBundleId())) {
            throw new IllegalStateException("Benchmark 报告未使用本次发布候选的目标运行身份");
        }
    }

    private GenerationBenchmarkWorkerResult result(
            GenerationBenchmarkWorkerResult.Status status,
            GenerationBenchmarkEvidenceCandidateIdentity identity,
            long candidatePhysicalRequestCount,
            String evidenceId,
            java.util.List<String> violations,
            GenerationBenchmarkReport report) {
        return new GenerationBenchmarkWorkerResult(
                GenerationBenchmarkWorkerResult.CURRENT_SCHEMA_VERSION,
                status,
                identity.subjectType(),
                identity.subjectKey(),
                identity.candidateFingerprint(),
                candidatePhysicalRequestCount,
                evidenceId,
                violations,
                report
        );
    }
}
