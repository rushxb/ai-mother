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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Seeds and grades an exact homepage brand-copy edit. */
@Component
public class BrandCopyBenchmarkRule implements GenerationBenchmarkValidationRule {

    private static final String TASK_ID = "edit_copy";
    private static final String RULE_ID = "brand_copy";
    private static final String OLD_BRAND = "基准旧品牌";
    private static final String TARGET_BRAND = "星河工作室";
    private static final Pattern HERO_TITLE = Pattern.compile(
            "(export\\s+const\\s+hero[\\s\\S]*?\\btitle\\s*:\\s*)'[^']*'");

    private final GenerationBenchmarkWorkspaceInspector inspector;

    public BrandCopyBenchmarkRule(GenerationBenchmarkWorkspaceInspector inspector) {
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

    @Override
    public boolean supports(GenerationBenchmarkTask task) {
        return task != null && TASK_ID.equals(task.id());
    }

    @Override
    public void prepare(GenerationBenchmarkTask task, GenerationWorkspace workspace) {
        String path = "src/data/siteData.ts";
        String content = inspector.readUtf8(workspace.frontendRootPath(), path);
        Matcher matcher = HERO_TITLE.matcher(content);
        if (!matcher.find()) {
            throw new IllegalStateException("benchmark hero title fixture could not be seeded");
        }
        String seeded = matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + "'" + OLD_BRAND + "'"));
        inspector.writeUtf8(workspace.frontendRootPath(), path, seeded);
    }

    @Override
    public GenerationBenchmarkRuleResult evaluate(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace,
            GenerationBenchmarkWorkspaceSnapshot baseline
    ) {
        String content = inspector.readUtf8(workspace.frontendRootPath(), "src/data/siteData.ts");
        boolean passed = content.contains(TARGET_BRAND) && !content.contains(OLD_BRAND);
        return passed
                ? GenerationBenchmarkRuleResult.passed(RULE_ID, dimension())
                : new GenerationBenchmarkRuleResult(
                        RULE_ID, dimension(), false, List.of("target_brand_not_applied"), 0);
    }
}
