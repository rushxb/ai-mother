package com.rush.rushaicodemother.orchestration.benchmark.rule;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
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
import java.util.Map;

/** Verifies that a benchmark produced the minimum runnable project structure for its target type. */
@Component
public class WorkspaceStructuralBenchmarkRule implements GenerationBenchmarkValidationRule {

    private static final String RULE_ID = "workspace_structure";

    private final GenerationBenchmarkWorkspaceInspector inspector;

    public WorkspaceStructuralBenchmarkRule(GenerationBenchmarkWorkspaceInspector inspector) {
        this.inspector = inspector;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public GenerationBenchmarkQualityDimension dimension() {
        return GenerationBenchmarkQualityDimension.STRUCTURAL;
    }

    @Override
    public boolean supports(GenerationBenchmarkTask task) {
        return task != null;
    }

    @Override
    public GenerationBenchmarkRuleResult evaluate(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace,
            GenerationBenchmarkWorkspaceSnapshot baseline
    ) {
        GenerationBenchmarkWorkspaceSnapshot current = inspector.capture(workspace.canonicalRootPath());
        Map<String, String> files = current.fileDigests();
        List<String> violations = new ArrayList<>();
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(task.codeGenType());
        if (files.isEmpty()) {
            violations.add("workspace_empty");
        } else if (type == CodeGenTypeEnum.VUE_PROJECT) {
            require(files, "package.json", violations, "frontend_manifest_missing");
            if (files.keySet().stream().noneMatch(path -> path.startsWith("src/") && path.endsWith(".vue"))) {
                violations.add("frontend_source_missing");
            }
        } else if (type == CodeGenTypeEnum.BACKEND_PROJECT) {
            require(files, "go.mod", violations, "backend_manifest_missing");
            if (files.keySet().stream().noneMatch(path -> path.endsWith(".go"))) {
                violations.add("backend_source_missing");
            }
        } else if (type == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            require(files, "frontend/package.json", violations, "frontend_manifest_missing");
            require(files, "backend/go.mod", violations, "backend_manifest_missing");
            if (files.keySet().stream().noneMatch(path -> path.startsWith("frontend/src/") && path.endsWith(".vue"))) {
                violations.add("frontend_source_missing");
            }
            if (files.keySet().stream().noneMatch(path -> path.startsWith("backend/") && path.endsWith(".go"))) {
                violations.add("backend_source_missing");
            }
        }
        return new GenerationBenchmarkRuleResult(
                RULE_ID, dimension(), violations.isEmpty(), violations, 0);
    }

    private void require(Map<String, String> files,
                         String path,
                         List<String> violations,
                         String violation) {
        if (!files.containsKey(path)) {
            violations.add(violation);
        }
    }
}
