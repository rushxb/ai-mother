package com.rush.rushaicodemother.orchestration.create.recipe;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.CreateSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.*;

/** Converts normalized CREATE specifications into renderer-safe fields and feature options. */
final class RecipeSpecSupport {

    private RecipeSpecSupport() {
    }

    static List<RecipeField> normalizeFields(List<CreateSpec.FieldSpec> fields) {
        List<RecipeField> normalized = new ArrayList<>();
        if (fields != null) {
            for (CreateSpec.FieldSpec field : fields) {
                String value = lowerIdentifier(firstNonBlank(field == null ? null : field.name(), ""));
                if (StrUtil.isNotBlank(value) && normalized.stream().noneMatch(item -> item.name().equals(value))
                        && normalized.size() < 6 && !"id".equals(value)) {
                    normalized.add(new RecipeField(
                            value,
                            normalizeFieldType(field == null ? null : field.type()),
                            firstNonBlank(field == null ? null : field.label(), fieldLabel(value)),
                            field != null && field.required()
                    ));
                }
            }
        }
        if (normalized.isEmpty()) {
            normalized.addAll(List.of(
                    new RecipeField("name", "string", "名称", true),
                    new RecipeField("status", "enum", "状态", false),
                    new RecipeField("owner", "string", "负责人", false),
                    new RecipeField("remark", "string", "备注", false)
            ));
        }
        if (normalized.stream().noneMatch(field -> "status".equals(field.name()))) {
            normalized.add(new RecipeField("status", "enum", "状态", false));
        }
        return normalized.stream().limit(6).toList();
    }

    static String normalizeFieldType(String type) {
        String normalized = StrUtil.blankToDefault(type, "string").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "integer", "int", "number" -> "integer";
            case "decimal", "float", "double", "money" -> "decimal";
            case "boolean", "bool" -> "boolean";
            case "datetime", "date", "time" -> "datetime";
            case "enum" -> "enum";
            case "text" -> "text";
            default -> "string";
        };
    }

    static String goType(String type) {
        return switch (normalizeFieldType(type)) {
            case "integer" -> "int";
            case "decimal" -> "float64";
            case "boolean" -> "bool";
            case "datetime" -> "time.Time";
            default -> "string";
        };
    }

    static String sqlType(String type) {
        return switch (normalizeFieldType(type)) {
            case "integer", "boolean" -> "integer";
            case "decimal" -> "real";
            case "datetime" -> "timestamp";
            default -> "text";
        };
    }

    static String sqlDefault(String type) {
        return switch (normalizeFieldType(type)) {
            case "integer", "boolean" -> " default 0";
            case "decimal" -> " default 0";
            case "datetime" -> " default current_timestamp";
            default -> " default ''";
        };
    }

    static FrontendOptions frontendOptions(CreateSpec spec) {
        CreateSpec.Frontend frontend = spec.frontend();
        List<String> styleKeywords = frontend == null || frontend.styleKeywords() == null ? List.of() : frontend.styleKeywords();
        String density = normalizeDensity(frontend == null ? null : frontend.density());
        List<String> interactions = normalizeInteraction(frontend == null ? List.of() : frontend.interaction());
        List<String> dataViz = normalizeDataViz(frontend == null ? List.of() : frontend.dataViz());
        List<String> navigation = frontend == null || frontend.navigation() == null ? List.of() : frontend.navigation().stream()
                .filter(StrUtil::isNotBlank)
                .map(String::strip)
                .limit(8)
                .toList();
        String radius = firstNonBlank(frontend == null || frontend.theme() == null ? null : frontend.theme().radius(), "8px");
        List<String> styleClasses = styleKeywords.stream().map(RecipeSpecSupport::styleClass).filter(StrUtil::isNotBlank).distinct().toList();
        String surfaceMuted = styleKeywords.stream().anyMatch(value -> containsAny(value, "医疗", "可信")) ? "#ecfeff"
                : styleKeywords.stream().anyMatch(value -> containsAny(value, "教育", "温暖")) ? "#fff7ed"
                : styleKeywords.stream().anyMatch(value -> containsAny(value, "高级", "premium")) ? "#f5f3ff"
                : "#f8fafc";
        return new FrontendOptions(density, styleKeywords, styleClasses, interactions, dataViz, navigation, radius, surfaceMuted);
    }

    static BackendOptions backendOptions(CreateSpec spec) {
        CreateSpec.Backend backend = spec.backend();
        CreateSpec.Database database = spec.database();
        boolean softDelete = backend == null || backend.softDelete();
        if (database != null) {
            softDelete = database.softDelete();
        }
        List<String> validationRules = backend == null || backend.validationRules() == null ? List.of() : backend.validationRules();
        return new BackendOptions(
                backend == null || backend.pagination(),
                backend == null || backend.search(),
                backend != null && backend.sort(),
                softDelete,
                backend != null && backend.authRequired(),
                backend != null && backend.importExport(),
                backend != null && backend.batchActions(),
                validationRules.stream().anyMatch(value -> containsAny(value, "required", "必填"))
        );
    }

    static List<String> databaseIndexes(CreateSpec spec, List<RecipeField> fields) {
        Set<String> allowed = fields.stream().map(field -> snake(field.name())).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> requested = spec.database() == null || spec.database().indexes() == null ? List.of() : spec.database().indexes();
        LinkedHashSet<String> indexes = new LinkedHashSet<>();
        for (String index : requested) {
            String normalized = snake(StrUtil.blankToDefault(index, ""));
            if (allowed.contains(normalized)) {
                indexes.add(normalized);
            }
        }
        if (indexes.isEmpty() && !fields.isEmpty()) {
            indexes.add(snake(fields.getFirst().name()));
        }
        if (fields.stream().anyMatch(field -> "status".equals(field.name()))) {
            indexes.add("status");
        }
        return indexes.stream().limit(4).toList();
    }

    static String normalizeDensity(String density) {
        String normalized = StrUtil.blankToDefault(density, "comfortable").toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "compact", "紧凑", "高信息密度")) return "compact";
        if (containsAny(normalized, "editorial", "内容", "展示")) return "editorial";
        return "comfortable";
    }

    static List<String> normalizeInteraction(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            if (containsAny(value, "筛选", "过滤", "filter", "搜索")) result.add("filter");
            if (containsAny(value, "分页", "page", "pagination")) result.add("pagination");
            if (containsAny(value, "批量", "batch")) result.add("batch");
            if (containsAny(value, "抽屉", "drawer", "详情")) result.add("drawer");
            if (containsAny(value, "导出", "export", "导入")) result.add("export");
            if (containsAny(value, "排序", "sort")) result.add("sort");
        }
        if (result.isEmpty()) {
            result.addAll(List.of("filter", "pagination"));
        }
        return result.stream().toList();
    }

    static List<String> normalizeDataViz(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            if (containsAny(value, "指标", "metric", "card")) result.add("metrics");
            if (containsAny(value, "趋势", "trend", "折线")) result.add("trend");
            if (containsAny(value, "漏斗", "funnel")) result.add("funnel");
            if (containsAny(value, "排行", "ranking", "rank")) result.add("ranking");
            if (containsAny(value, "日历", "calendar")) result.add("calendar");
        }
        if (result.isEmpty()) {
            result.addAll(List.of("metrics", "trend"));
        }
        return result.stream().toList();
    }

    static String styleClass(String keyword) {
        if (containsAny(keyword, "运营", "中台", "专业")) return "style-ops";
        if (containsAny(keyword, "高级", "premium")) return "style-premium";
        if (containsAny(keyword, "医疗", "可信")) return "style-medical-trust";
        if (containsAny(keyword, "教育", "温暖")) return "style-education-warm";
        return "";
    }

    static DensityTokens densityTokens(String density) {
        return switch (normalizeDensity(density)) {
            case "compact" -> new DensityTokens("12px", "12px", "40px", "13px", "4px 8px");
            case "editorial" -> new DensityTokens("28px", "24px", "56px", "15px", "8px 12px");
            default -> new DensityTokens("18px", "16px", "48px", "14px", "6px 10px");
        };
    }
}
