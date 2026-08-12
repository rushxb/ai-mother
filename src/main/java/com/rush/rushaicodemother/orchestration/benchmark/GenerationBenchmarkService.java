package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;

/**
 * 生成基准测试服务实现。
 */
@Service
@RequiredArgsConstructor
public class GenerationBenchmarkService {

    private final GenerationBenchmarkRunner runner;
    private final OrchestratedGenerationBenchmarkExecutor orchestratedExecutor;
    private final GenerationBenchmarkReportValidator reportValidator;
    private final GenerationBenchmarkReleaseGate releaseGate;

    public GenerationBenchmarkReport runEndToEndCatalog() {
        return runner.run(orchestratedExecutor);
    }

    /** 对同一数据集、Prompt 和模型身份运行三组规划层消融实验。 */
    public GenerationPlanningAblationReport runPlanningAblation() {
        EnumMap<GenerationPlanningVariant, GenerationBenchmarkReport> reports =
                new EnumMap<>(GenerationPlanningVariant.class);
        for (GenerationPlanningVariant variant : GenerationPlanningVariant.values()) {
            GenerationBenchmarkReport report = runner.run(
                    task -> orchestratedExecutor.execute(task, variant));
            reportValidator.validate(report);
            reports.put(variant, report);
        }
        return GenerationPlanningAblationReport.from(reports);
    }

    public GenerationPlanningAblationAssessment runPlanningAblationGate(
            GenerationPlanningVariant candidate,
            GenerationPlanningVariant baseline) {
        return releaseGate.assessPlanningCandidate(
                runPlanningAblation(), candidate, baseline);
    }

    /**
 * 运行发布门禁处理流程。
 *
 * @return 发布门禁
 */
    public GenerationBenchmarkReleaseAssessment runReleaseGate() {
        GenerationBenchmarkReport report = runEndToEndCatalog();
        reportValidator.validate(report);
        return releaseGate.assess(report);
    }
}
