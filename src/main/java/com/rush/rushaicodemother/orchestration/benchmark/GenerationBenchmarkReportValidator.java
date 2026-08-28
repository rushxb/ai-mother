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
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (report == null) {
            throw invalid("生成质量评测报告不能为空");
        }
        if (report.schemaVersion() != GenerationBenchmarkReport.CURRENT_SCHEMA_VERSION) {
            throw invalid("生成质量评测报告版本不受支持");
        }
        Map<String, GenerationBenchmarkTask> expectedTasks = new HashMap<>();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (GenerationBenchmarkTask task : catalog.tasks()) {
            expectedTasks.put(task.id(), task);
        }
        Set<String> seen = new HashSet<>();
        for (GenerationBenchmarkRunResult result : report.results()) {
            GenerationBenchmarkTask task = expectedTasks.get(result.taskId());
            if (task == null) {
                throw invalid("生成质量评测报告包含未知任务");
            }
            if (!seen.add(result.taskId())) {
                throw invalid("生成质量评测报告包含重复任务");
            }
            if (!task.mode().equals(result.mode())) {
                throw invalid("生成质量评测报告的任务模式与数据集不一致");
            }
            if (!result.expectedRoute().isBlank()
                    && (!task.expectedRoute().equals(result.expectedRoute())
                    || !result.routeAllowed())) {
                throw invalid("生成质量评测报告包含不符合约束的路由结果");
            }
            validateFallback(task, result);
        }
        if (seen.size() != expectedTasks.size() || !seen.containsAll(expectedTasks.keySet())) {
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

    private void validateFallback(GenerationBenchmarkTask task,
                                  GenerationBenchmarkRunResult result) {
        if (task.fallbackExpectation() == GenerationBenchmarkFallbackExpectation.REQUIRED
                && !result.fallback()) {
            throw invalid("生成质量评测报告缺少数据集要求的回退事实");
        }
        if (task.fallbackExpectation() == GenerationBenchmarkFallbackExpectation.FORBIDDEN
                && result.fallback()) {
            throw invalid("生成质量评测报告包含数据集禁止的回退事实");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.OPERATION_ERROR, message);
    }
}
