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
import java.util.Locale;
import java.util.Set;

/** Rejects broad or dependency-changing edits when a benchmark only requires source changes. */
@Component
public class EditDiffScopeBenchmarkRule implements GenerationBenchmarkValidationRule {

    private static final String RULE_ID = "edit_diff_scope";
    private static final Set<String> PROTECTED_FILE_NAMES = Set.of(
            "package.json", "pnpm-lock.yaml", "package-lock.json", "yarn.lock",
            "go.mod", "go.sum", "vite.config.ts", "vite.config.js", "tsconfig.json"
    );

    private final GenerationBenchmarkWorkspaceInspector inspector;

    public EditDiffScopeBenchmarkRule(GenerationBenchmarkWorkspaceInspector inspector) {
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
        return task != null && !"CREATE".equalsIgnoreCase(task.mode());
    }

    @Override
    public GenerationBenchmarkRuleResult evaluate(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace,
            GenerationBenchmarkWorkspaceSnapshot baseline
    ) {
        Set<String> changed = baseline.changedPaths(inspector.capture(workspace.canonicalRootPath()));
        List<String> violations = new ArrayList<>();
        if (changed.isEmpty()) {
            violations.add("no_source_change");
        }
        int maxChangedFiles = "LIGHT_EDIT".equalsIgnoreCase(task.mode()) ? 6 : 24;
        if (changed.size() > maxChangedFiles) {
            violations.add("changed_file_count_above_maximum");
        }
        if (changed.stream().map(this::fileName).anyMatch(PROTECTED_FILE_NAMES::contains)) {
            violations.add("protected_dependency_file_changed");
        }
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(task.codeGenType());
        if (changed.stream().anyMatch(path -> !allowedSourcePath(type, path))) {
            violations.add("change_outside_expected_source_scope");
        }
        return new GenerationBenchmarkRuleResult(
                RULE_ID, dimension(), violations.isEmpty(), violations, changed.size());
    }

    private boolean allowedSourcePath(CodeGenTypeEnum type, String path) {
        if (type == CodeGenTypeEnum.VUE_PROJECT) {
            return path.startsWith("src/");
        }
        if (type == CodeGenTypeEnum.BACKEND_PROJECT) {
            return startsWithAny(path, "internal/", "cmd/", "migrations/", "api/");
        }
        if (type == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return startsWithAny(path,
                    "frontend/src/", "backend/internal/", "backend/cmd/", "backend/migrations/");
        }
        return true;
    }

    private boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String fileName(String path) {
        int separator = path.lastIndexOf('/');
        return (separator < 0 ? path : path.substring(separator + 1)).toLowerCase(Locale.ROOT);
    }
}
