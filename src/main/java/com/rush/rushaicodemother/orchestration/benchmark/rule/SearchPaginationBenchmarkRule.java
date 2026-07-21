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
import java.util.Locale;

/** Seeds a static product table and checks for real search and pagination implementation signals. */
@Component
public class SearchPaginationBenchmarkRule implements GenerationBenchmarkValidationRule {

    private static final String TASK_ID = "edit_search_pagination";
    private static final String RULE_ID = "search_pagination";
    private static final String COMPONENT = "BenchmarkProductTable";

    private final GenerationBenchmarkWorkspaceInspector inspector;

    public SearchPaginationBenchmarkRule(GenerationBenchmarkWorkspaceInspector inspector) {
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
        VueBenchmarkRuleSupport.mountProbe(inspector, workspace, COMPONENT, """
                <template>
                  <section>
                    <h2>商品列表</h2>
                    <table>
                      <tbody>
                        <tr v-for="product in products" :key="product.id">
                          <td>{{ product.name }}</td>
                        </tr>
                      </tbody>
                    </table>
                  </section>
                </template>

                <script setup lang="ts">
                const products = [
                  { id: 1, name: '键盘' },
                  { id: 2, name: '鼠标' },
                  { id: 3, name: '显示器' },
                  { id: 4, name: '耳机' }
                ]
                </script>
                """);
    }

    @Override
    public GenerationBenchmarkRuleResult evaluate(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace,
            GenerationBenchmarkWorkspaceSnapshot baseline
    ) {
        String content = inspector.readUtf8(
                workspace.frontendRootPath(), "src/benchmark/" + COMPONENT + ".vue");
        String normalized = content.toLowerCase(Locale.ROOT);
        boolean hasSearchBinding = containsAny(normalized,
                "type=\"search\"", "v-model", "@input", "keyword", "query");
        boolean hasSearchTransformation = containsAny(normalized,
                ".filter(", ".includes(", ".indexof(", "filteredproducts", "searchresults");
        boolean hasSearch = hasSearchBinding && hasSearchTransformation;
        boolean hasPaginationState = containsAny(normalized,
                "currentpage", "current-page", "pagesize", "page-size", "pagenumber");
        boolean hasPaginationWindow = containsAny(normalized,
                ".slice(", "paginatedproducts", "pagedproducts", "pageitems", "offset");
        boolean hasPaginationControl = containsAny(normalized,
                "上一页", "下一页", "previous", "next", "pagination", "@current-change");
        boolean hasPagination = hasPaginationState && hasPaginationWindow && hasPaginationControl;
        List<String> violations = new ArrayList<>();
        if (!hasSearch) {
            violations.add("search_behavior_missing");
        }
        if (!hasPagination) {
            violations.add("pagination_behavior_missing");
        }
        return new GenerationBenchmarkRuleResult(
                RULE_ID, dimension(), violations.isEmpty(), violations, 0);
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
