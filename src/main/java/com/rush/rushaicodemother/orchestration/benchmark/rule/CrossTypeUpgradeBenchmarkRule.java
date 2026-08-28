package com.rush.rushaicodemother.orchestration.benchmark.rule;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkValidationRule;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceInspector;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceSnapshot;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 评估跨工程类型升级的迁移边界。
 *
 * <p>普通编辑的文件数量和依赖清单约束不适用于工程布局迁移；本规则以已冻结的
 * 来源/目标身份和逻辑相对路径摘要证明迁移确实发生。业务内容保留与目标工程结构
 * 继续分别由声明式功能断言和结构评分器负责，避免一个规则重复拥有多个事实。</p>
 */
@Component
public class CrossTypeUpgradeBenchmarkRule implements GenerationBenchmarkValidationRule {

    private static final String RULE_ID = "cross_type_upgrade_scope";

    private final GenerationBenchmarkWorkspaceInspector inspector;

    public CrossTypeUpgradeBenchmarkRule(GenerationBenchmarkWorkspaceInspector inspector) {
        this.inspector = inspector;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public GenerationBenchmarkQualityDimension dimension() {
        return GenerationBenchmarkQualityDimension.DIFF_SCOPE;
    }

    @Override
    public boolean supports(GenerationBenchmarkTask task) {
        return task != null && task.crossTypeUpgrade();
    }

    @Override
    public int order() {
        return 21;
    }

    @Override
    public GenerationBenchmarkRuleResult evaluate(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace,
            GenerationBenchmarkWorkspaceSnapshot baseline
    ) {
        List<String> violations = new ArrayList<>();
        if (workspace.codeGenType() != task.targetProjectType()) {
            violations.add("migration_target_type_mismatch");
        }
        if (baseline.fileDigests().isEmpty()) {
            violations.add("migration_source_workspace_empty");
        }
        Set<String> changedPaths;
        try {
            changedPaths = baseline.changedPaths(inspector.capture(workspace));
        } catch (IllegalArgumentException identityMismatch) {
            violations.add("migration_workspace_identity_mismatch");
            changedPaths = Set.of();
        }
        if (changedPaths.isEmpty()) {
            violations.add("migration_workspace_unchanged");
        }
        return new GenerationBenchmarkRuleResult(
                RULE_ID,
                dimension(),
                violations.isEmpty(),
                List.copyOf(violations),
                changedPaths.size()
        );
    }
}
