package com.rush.rushaicodemother.orchestration.benchmark.rule;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkValidationRule;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceInspector;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceSnapshot;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Component;

import java.util.List;

/** 引入真正的 TypeScript 缺失导入构建失败并验证它是否已解决。 */
@Component
public class MissingImportBenchmarkRule implements GenerationBenchmarkValidationRule {

    private static final String TASK_ID = "edit_build_error";
    private static final String RULE_ID = "missing_import_repair";
    private static final String PROBE_PATH = "src/benchmark/benchmarkBrokenImport.ts";
    private static final String MISSING_MODULE = "./benchmarkMissingModule";
    private static final List<String> MODULE_CANDIDATES = List.of(
            "src/benchmark/benchmarkMissingModule.ts",
            "src/benchmark/benchmarkMissingModule.tsx",
            "src/benchmark/benchmarkMissingModule.js",
            "src/benchmark/benchmarkMissingModule.jsx",
            "src/benchmark/benchmarkMissingModule.mts",
            "src/benchmark/benchmarkMissingModule.mjs",
            "src/benchmark/benchmarkMissingModule.vue"
    );

    private final GenerationBenchmarkWorkspaceInspector inspector;

    public MissingImportBenchmarkRule(GenerationBenchmarkWorkspaceInspector inspector) {
        this.inspector = inspector;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public GenerationBenchmarkQualityDimension dimension() {
        return GenerationBenchmarkQualityDimension.FUNCTIONAL;
    }

    /**
 * 返回{@code supports}。
 *
 * @param task 任务
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean supports(GenerationBenchmarkTask task) {
        return task != null && TASK_ID.equals(task.id());
    }

    /**
 * 准备后续流程所需的{@code Missing}导入基准测试规则。
 *
 * @param task 任务
 * @param workspace 工作区
 */
    @Override
    public void prepare(GenerationBenchmarkTask task, GenerationWorkspace workspace) {
        inspector.writeUtf8(workspace.frontendRootPath(), PROBE_PATH, """
                import { benchmarkValue } from './benchmarkMissingModule'

                export const benchmarkBuildProbe = benchmarkValue
                """);
    }

    /**
 * 返回{@code evaluate}。
 *
 * @param task 任务
 * @param workspace 工作区
 * @param baseline {@code baseline} 对应的调用参数
 * @return {@code Missing}导入基准测试规则
 */
    @Override
    public GenerationBenchmarkRuleResult evaluate(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace,
            GenerationBenchmarkWorkspaceSnapshot baseline
    ) {
        if (!inspector.exists(workspace.frontendRootPath(), PROBE_PATH)) {
            return GenerationBenchmarkRuleResult.passed(RULE_ID, dimension());
        }
        String content = inspector.readUtf8(workspace.frontendRootPath(), PROBE_PATH);
        boolean moduleCreated = MODULE_CANDIDATES.stream()
                .anyMatch(path -> inspector.exists(workspace.frontendRootPath(), path));
        boolean passed = moduleCreated || !content.contains(MISSING_MODULE);
        return passed
                ? GenerationBenchmarkRuleResult.passed(RULE_ID, dimension())
                : GenerationBenchmarkRuleResult.failed(
                        RULE_ID, dimension(), "missing_import_still_unresolved");
    }
}
