package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 校验报告精确覆盖当前数据集，并重算所有汇总统计。 */
@Component
public class GenerationBenchmarkReportValidator {

    private final GenerationBenchmarkCatalog catalog;
    private final GenerationBenchmarkRunner runner;

    public GenerationBenchmarkReportValidator(GenerationBenchmarkCatalog catalog,
                                              GenerationBenchmarkRunner runner) {
        this.catalog = catalog;
        this.runner = runner;
    }

    public void validate(GenerationBenchmarkReport report) {
        if (report == null) {
            throw invalid("生成质量评测报告不能为空");
        }
        if (report.schemaVersion() != GenerationBenchmarkReport.CURRENT_SCHEMA_VERSION) {
            throw invalid("生成质量评测报告版本不受支持");
        }
        Map<String, String> expectedModes = new HashMap<>();
        for (GenerationBenchmarkTask task : catalog.tasks()) {
            expectedModes.put(task.id(), task.mode());
        }
        Set<String> seen = new HashSet<>();
        for (GenerationBenchmarkRunResult result : report.results()) {
            String expectedMode = expectedModes.get(result.taskId());
            if (expectedMode == null) {
                throw invalid("生成质量评测报告包含未知任务");
            }
            if (!seen.add(result.taskId())) {
                throw invalid("生成质量评测报告包含重复任务");
            }
            if (!expectedMode.equals(result.mode())) {
                throw invalid("生成质量评测报告的任务模式与数据集不一致");
            }
        }
        if (seen.size() != expectedModes.size() || !seen.containsAll(expectedModes.keySet())) {
            throw invalid("生成质量评测报告未覆盖完整数据集");
        }
        GenerationBenchmarkReport recalculated = runner.summarize(
                report.results(),
                report.promptBundleId(),
                report.modelFingerprint()
        );
        if (!recalculated.equals(report)) {
            throw invalid("生成质量评测报告的汇总统计与任务明细不一致");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.OPERATION_ERROR, message);
    }
}
