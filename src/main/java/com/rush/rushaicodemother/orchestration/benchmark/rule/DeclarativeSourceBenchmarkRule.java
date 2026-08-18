package com.rush.rushaicodemother.orchestration.benchmark.rule;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkDeclarationValidator;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkFixtureFile;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkSourceAssertion;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkSourceRoot;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkValidationRule;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceInspector;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceSnapshot;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 执行数据集声明的固定源码夹具和字符串断言。 */
@Component
public class DeclarativeSourceBenchmarkRule implements GenerationBenchmarkValidationRule {

    private static final String RULE_ID = "declared_source_behavior";

    private final GenerationBenchmarkWorkspaceInspector inspector;

    public DeclarativeSourceBenchmarkRule(GenerationBenchmarkWorkspaceInspector inspector) {
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
        return task != null && !task.sourceAssertions().isEmpty();
    }

    @Override
    public int order() {
        return 10;
    }

    /**
 * 准备后续流程所需的{@code Declarative}来源基准测试规则。
 *
 * @param task 任务
 * @param workspace 工作区
 */
    @Override
    public void prepare(GenerationBenchmarkTask task, GenerationWorkspace workspace) {
        GenerationBenchmarkDeclarationValidator.validate(task);
        for (GenerationBenchmarkFixtureFile fixture : task.fixtureFiles()) {
            inspector.writeUtf8(
                    resolveRoot(workspace, fixture.root()),
                    fixture.path(),
                    fixture.content()
            );
        }
    }

    @Override
    public GenerationBenchmarkRuleResult evaluate(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace,
            GenerationBenchmarkWorkspaceSnapshot baseline
    ) {
        GenerationBenchmarkDeclarationValidator.validate(task);
        Map<SourceFile, String> contents = loadSources(task, workspace);
        List<String> violations = new ArrayList<>();
        for (GenerationBenchmarkSourceAssertion assertion : task.sourceAssertions()) {
            List<String> scopedContents = assertion.paths().stream()
                    .map(path -> contents.get(new SourceFile(assertion.root(), path)))
                    .toList();
            if (!assertion.allOf().stream().allMatch(token -> contains(scopedContents, token))) {
                violations.add(assertion.id() + "_all_of_missing");
            }
            if (!assertion.anyOf().isEmpty()
                    && assertion.anyOf().stream().noneMatch(token -> contains(scopedContents, token))) {
                violations.add(assertion.id() + "_any_of_missing");
            }
            if (assertion.noneOf().stream().anyMatch(token -> contains(scopedContents, token))) {
                violations.add(assertion.id() + "_forbidden_present");
            }
        }
        return new GenerationBenchmarkRuleResult(
                RULE_ID,
                dimension(),
                violations.isEmpty(),
                violations,
                0
        );
    }

    /** 加载{@code Sources}。 */
    private Map<SourceFile, String> loadSources(GenerationBenchmarkTask task,
                                                GenerationWorkspace workspace) {
        Map<SourceFile, String> contents = new LinkedHashMap<>();
        int totalChars = 0;
        for (GenerationBenchmarkSourceAssertion assertion : task.sourceAssertions()) {
            for (String path : assertion.paths()) {
                SourceFile source = new SourceFile(assertion.root(), path);
                if (contents.containsKey(source)) {
                    continue;
                }
                if (contents.size() >= GenerationBenchmarkDeclarationValidator.MAX_SOURCE_FILES) {
                    throw new IllegalStateException("评测源码读取文件数超过上限");
                }
                String content = inspector.readUtf8(
                        resolveRoot(workspace, source.root()),
                        source.path(),
                        GenerationBenchmarkDeclarationValidator.MAX_SOURCE_FILE_CHARS
                );
                totalChars = Math.addExact(totalChars, content.length());
                if (totalChars > GenerationBenchmarkDeclarationValidator.MAX_SOURCE_TOTAL_CHARS) {
                    throw new IllegalStateException("评测源码读取总字符数超过上限");
                }
                contents.put(source, content);
            }
        }
        return Map.copyOf(contents);
    }

    private boolean contains(List<String> contents, String token) {
        return contents.stream().anyMatch(content -> content.contains(token));
    }

    /** 根据当前上下文解析根。 */
    private Path resolveRoot(GenerationWorkspace workspace, GenerationBenchmarkSourceRoot root) {
        if (workspace == null || root == null) {
            throw new IllegalArgumentException("评测源码工作区或根目录不能为空");
        }
        Path path = switch (root) {
            case WORKSPACE -> workspace.canonicalRootPath();
            case FRONTEND -> workspace.frontendRootPath();
            case BACKEND -> workspace.backendRootPath();
        };
        if (path == null) {
            throw new IllegalArgumentException("评测源码根目录与工程类型不匹配");
        }
        return path;
    }

    private record SourceFile(GenerationBenchmarkSourceRoot root, String path) {
    }
}
