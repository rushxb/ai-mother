package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TemplateVariableEngine {

    /**
 * 返回清单。
 *
 * @param templateId 模板编号
 * @param spec {@code spec} 对应的调用参数
 * @return 模板变量
 */
    public TemplateVariableManifest manifest(String templateId, CreateSpec spec) {
        Map<String, Object> variables = new LinkedHashMap<>();
        CreateSpec.EntitySpec primary = spec.entities().isEmpty() ? null : spec.entities().getFirst();
        variables.put("product.appType", spec.product().appType());
        variables.put("product.domain", spec.product().domain());
        variables.put("product.brandName", spec.product().brandName());
        variables.put("frontend.layout", spec.frontend().layout());
        variables.put("frontend.styleKeywords", spec.frontend().styleKeywords());
        variables.put("frontend.density", spec.frontend().density());
        variables.put("frontend.navigation", spec.frontend().navigation());
        variables.put("frontend.components", spec.frontend().componentPreference());
        variables.put("frontend.theme.primary", spec.frontend().theme().primary());
        variables.put("frontend.theme.accent", spec.frontend().theme().accent());
        variables.put("backend.apiStyle", spec.backend().apiStyle());
        variables.put("backend.pagination", spec.backend().pagination());
        variables.put("backend.search", spec.backend().search());
        variables.put("backend.softDelete", spec.backend().softDelete());
        variables.put("database.indexes", spec.database().indexes());
        variables.put("content.mockDataStyle", spec.content().mockDataStyle());
        variables.put("content.menu", spec.content().menu());
        if (primary != null) {
            variables.put("entities.primary.name", primary.name());
            variables.put("entities.primary.label", primary.label());
            variables.put("entities.primary.fields", primary.fields());
        }
        return new TemplateVariableManifest(
                templateId,
                variables,
                supportedModules(spec),
                renderTargets(templateId)
        );
    }

    private List<String> supportedModules(CreateSpec spec) {
        return spec.modules().stream().map(CreateSpec.ModuleSpec::id).toList();
    }

    private List<String> renderTargets(String templateId) {
        return switch (templateId) {
            case "vue-web-landing" -> List.of("src/data/landingData.ts");
            case "vue-web-admin" -> List.of("src/views/DashboardView.vue", "src/data/*.ts", "src/styles/theme.css");
            case "vue-web-basic" -> List.of("src/views/HomeView.vue", "src/data/*.ts", "src/styles/theme.css");
            case "vue-web-mobile" -> List.of("src/views/HomeView.vue", "src/data/*.ts", "src/styles/mobile.css");
            case "go-sqlite-backend-basic" -> List.of("internal/modules/*", "internal/domain/model.go", "sql/schema.sql", "cmd/server/main.go");
            default -> List.of();
        };
    }
}
