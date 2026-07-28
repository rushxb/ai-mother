package com.rush.rushaicodemother.orchestration.create;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.CreateSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 创建规格规范化器。
 */
@Component
public class CreateSpecNormalizer {

    private static final int MAX_ENTITIES = 4;
    private static final int MAX_FIELDS = 8;
    private static final Pattern IDENTIFIER_CLEANUP = Pattern.compile("[^A-Za-z0-9_]");
    private static final Set<String> RESERVED = Set.of(
            "type", "var", "select", "order", "group", "from", "where", "table", "index", "user", "func", "package",
            "map", "range", "interface", "struct", "delete", "default"
    );
    private static final Set<String> FIELD_TYPES = Set.of("string", "integer", "decimal", "boolean", "datetime", "enum", "text");
    private static final Map<String, String> CN_FIELD_NAMES = Map.ofEntries(
            Map.entry("标题", "title"),
            Map.entry("名称", "name"),
            Map.entry("课程名称", "title"),
            Map.entry("教练", "coach"),
            Map.entry("价格", "price"),
            Map.entry("状态", "status"),
            Map.entry("容量", "capacity"),
            Map.entry("负责人", "owner"),
            Map.entry("备注", "remark"),
            Map.entry("手机号", "phone"),
            Map.entry("邮箱", "email"),
            Map.entry("时间", "time")
    );

    private final CreateSpecDefaults defaults = new CreateSpecDefaults();
    private final CreateSpecSafetySanitizer sanitizer = new CreateSpecSafetySanitizer();

    /**
 * 规范化创建{@code Spec}规范化器。
 *
 * @param source 来源数据
 * @param userMessage 用户消息
 * @param plan 计划
 * @param group 分组
 * @return 创建{@code Spec}规范化器
 */
    public NormalizedSpec normalize(CreateSpec source, String userMessage, CreateGenerationPlan plan, SlotGroup group) {
        List<String> warnings = new ArrayList<>();
        CreateSpec fallback = defaults.fromRequest(userMessage, plan, group, source == null ? "ai_spec_empty" : "ai_spec_partial");
        CreateSpec raw = source == null ? fallback : source;

        CreateSpec.Product product = normalizeProduct(raw.product(), fallback.product());
        CreateSpec.Frontend frontend = normalizeFrontend(raw.frontend(), fallback.frontend());
        List<CreateSpec.EntitySpec> entities = normalizeEntities(raw.entities(), fallback.entities(), warnings);
        CreateSpec.Backend backend = normalizeBackend(raw.backend(), fallback.backend());
        CreateSpec.Database database = normalizeDatabase(raw.database(), fallback.database(), entities);
        CreateSpec.Content content = normalizeContent(raw.content(), fallback.content(), product, frontend, entities);
        CreateSpec.Constraints constraints = normalizeConstraints(raw.constraints());
        List<CreateSpec.ModuleSpec> modules = normalizeModules(raw.modules(), fallback.modules());

        CreateSpec spec = new CreateSpec(product, modules, entities, frontend, backend, database, content, constraints);
        return new NormalizedSpec(spec, CreateSpecValidationResult.ok(warnings));
    }

    private CreateSpec.Product normalizeProduct(CreateSpec.Product raw, CreateSpec.Product fallback) {
        raw = raw == null ? fallback : raw;
        return new CreateSpec.Product(
                oneOf(safe(raw.appType(), 32, fallback.appType()), fallback.appType(), "landing", "admin", "backend", "mobile", "basic", "full_stack", "vue_project", "backend_project"),
                identifierLike(safe(raw.domain(), 48, fallback.domain()), fallback.domain()),
                safe(raw.brandName(), 32, fallback.brandName()),
                safe(raw.audience(), 64, fallback.audience()),
                safe(raw.businessGoal(), 96, fallback.businessGoal())
        );
    }

    /** 规范化{@code Frontend}。 */
    private CreateSpec.Frontend normalizeFrontend(CreateSpec.Frontend raw, CreateSpec.Frontend fallback) {
        raw = raw == null ? fallback : raw;
        return new CreateSpec.Frontend(
                oneOf(safe(raw.layout(), 40, fallback.layout()), fallback.layout(),
                        "sidebar_dashboard", "top_nav", "mobile_tabbar", "landing_scroll"),
                normalizeKeywords(raw.styleKeywords(), fallback.styleKeywords(), 6),
                oneOf(safe(raw.density(), 24, fallback.density()), fallback.density(), "compact", "comfortable", "editorial"),
                normalizeStringList(raw.componentPreference(), fallback.componentPreference(), 8, 32),
                normalizeStringList(raw.interaction(), fallback.interaction(), 8, 32),
                normalizeStringList(raw.dataViz(), fallback.dataViz(), 6, 32),
                normalizeStringList(raw.navigation(), fallback.navigation(), 8, 24),
                normalizeTheme(raw.theme(), fallback.theme())
        );
    }

    private CreateSpec.Theme normalizeTheme(CreateSpec.Theme raw, CreateSpec.Theme fallback) {
        raw = raw == null ? fallback : raw;
        return new CreateSpec.Theme(
                hex(raw.primary(), fallback.primary()),
                hex(raw.accent(), fallback.accent()),
                hex(raw.background(), fallback.background()),
                oneOf(safe(raw.radius(), 16, fallback.radius()), fallback.radius(), "4px", "6px", "8px", "10px", "12px"),
                oneOf(safe(raw.motion(), 20, fallback.motion()), fallback.motion(), "none", "light", "smooth")
        );
    }

    /** 规范化{@code Entities}。 */
    private List<CreateSpec.EntitySpec> normalizeEntities(List<CreateSpec.EntitySpec> raw,
                                                          List<CreateSpec.EntitySpec> fallback,
                                                          List<String> warnings) {
        List<CreateSpec.EntitySpec> source = raw == null || raw.isEmpty() ? fallback : raw;
        List<CreateSpec.EntitySpec> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (CreateSpec.EntitySpec entity : source) {
            if (entity == null || result.size() >= MAX_ENTITIES) {
                continue;
            }
            String name = pascalIdentifier(entity.name(), "Record");
            if (names.contains(name)) {
                warnings.add("duplicate_entity:" + name);
                continue;
            }
            names.add(name);
            String label = safe(entity.label(), 24, name);
            List<CreateSpec.FieldSpec> fields = normalizeFields(entity.fields(), fallback.getFirst().fields(), warnings);
            result.add(new CreateSpec.EntitySpec(
                    name,
                    label,
                    fields,
                    normalizeStringList(entity.relations(), List.of(), 4, 32),
                    normalizeStringList(entity.behaviors(), List.of("list", "create", "update", "delete"), 6, 24)
            ));
        }
        return result.isEmpty() ? fallback : result;
    }

    /** 规范化{@code Fields}。 */
    private List<CreateSpec.FieldSpec> normalizeFields(List<CreateSpec.FieldSpec> raw,
                                                       List<CreateSpec.FieldSpec> fallback,
                                                       List<String> warnings) {
        List<CreateSpec.FieldSpec> source = raw == null || raw.isEmpty() ? fallback : raw;
        List<CreateSpec.FieldSpec> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (CreateSpec.FieldSpec field : source) {
            if (field == null || result.size() >= MAX_FIELDS) {
                continue;
            }
            String name = lowerIdentifier(field.name(), CN_FIELD_NAMES.getOrDefault(safe(field.label(), 24, ""), "field"));
            if (StrUtil.isBlank(name) || "id".equals(name)) {
                continue;
            }
            if (RESERVED.contains(name)) {
                name = name + "Value";
            }
            if (names.contains(name)) {
                warnings.add("duplicate_field:" + name);
                continue;
            }
            names.add(name);
            String type = safe(field.type(), 24, "string").toLowerCase(Locale.ROOT);
            if (!FIELD_TYPES.contains(type)) {
                warnings.add("unsupported_field_type:" + type);
                type = "string";
            }
            result.add(new CreateSpec.FieldSpec(
                    name,
                    type,
                    safe(field.label(), 24, field.name()),
                    field.required(),
                    normalizeStringList(field.options(), List.of(), 8, 24)
            ));
        }
        if (result.isEmpty()) {
            return normalizeFields(fallback, List.of(new CreateSpec.FieldSpec("name", "string", "名称", true, List.of())), warnings);
        }
        if (result.stream().noneMatch(field -> "status".equals(field.name())) && result.size() < MAX_FIELDS) {
            result.add(new CreateSpec.FieldSpec("status", "enum", "状态", false, List.of("启用", "停用")));
        }
        return result;
    }

    /** 规范化后端。 */
    private CreateSpec.Backend normalizeBackend(CreateSpec.Backend raw, CreateSpec.Backend fallback) {
        raw = raw == null ? fallback : raw;
        return new CreateSpec.Backend(
                oneOf(safe(raw.apiStyle(), 16, fallback.apiStyle()), fallback.apiStyle(), "rest"),
                raw.authRequired(),
                raw.pagination(),
                raw.search(),
                raw.sort(),
                raw.softDelete(),
                normalizeStringList(raw.auditFields(), fallback.auditFields(), 4, 24),
                raw.importExport(),
                raw.batchActions(),
                normalizeStringList(raw.validationRules(), fallback.validationRules(), 8, 32),
                oneOf(safe(raw.errorStyle(), 24, fallback.errorStyle()), fallback.errorStyle(), "standard_json", "problem_json"),
                lowerIdentifier(raw.moduleName(), fallback.moduleName())
        );
    }

    /** 规范化数据库。 */
    private CreateSpec.Database normalizeDatabase(CreateSpec.Database raw,
                                                  CreateSpec.Database fallback,
                                                  List<CreateSpec.EntitySpec> entities) {
        raw = raw == null ? fallback : raw;
        List<CreateSpec.TableSpec> tables = entities.stream()
                .map(entity -> new CreateSpec.TableSpec(snake(entity.name()) + "s", entity.fields(), List.of(entity.fields().getFirst().name())))
                .toList();
        return new CreateSpec.Database(
                tables,
                normalizeStringList(raw.indexes(), fallback.indexes(), 8, 32),
                raw.softDelete(),
                oneOf(safe(raw.migrationStrategy(), 32, fallback.migrationStrategy()), fallback.migrationStrategy(), "append_sql_schema")
        );
    }

    private CreateSpec.Content normalizeContent(CreateSpec.Content raw,
                                                CreateSpec.Content fallback,
                                                CreateSpec.Product product,
                                                CreateSpec.Frontend frontend,
                                                List<CreateSpec.EntitySpec> entities) {
        raw = raw == null ? fallback : raw;
        return new CreateSpec.Content(
                safe(raw.tone(), 32, fallback.tone()),
                safe(raw.mockDataStyle(), 64, fallback.mockDataStyle()),
                normalizeStringList(raw.menu(), frontend.navigation(), 8, 24),
                normalizeStringList(raw.pages(), fallback.pages(), 8, 32),
                normalizeLanding(raw.landing(), fallback.landing(), product, entities)
        );
    }

    /** 规范化{@code Landing}。 */
    private CreateSpec.Landing normalizeLanding(CreateSpec.Landing raw,
                                                CreateSpec.Landing fallback,
                                                CreateSpec.Product product,
                                                List<CreateSpec.EntitySpec> entities) {
        raw = raw == null ? fallback : raw;
        String entityLabel = entities.isEmpty() ? "业务" : entities.getFirst().label();
        return new CreateSpec.Landing(
                safe(raw.headline(), 64, product.brandName() + " " + entityLabel + "解决方案"),
                safe(raw.description(), 140, fallback.description()),
                safe(raw.cta(), 16, fallback.cta()),
                safe(raw.secondary(), 16, fallback.secondary()),
                normalizeStringList(raw.nav(), fallback.nav(), 6, 16),
                normalizeStats(raw.stats(), fallback.stats()),
                normalizeTextBlocks(raw.highlights(), fallback.highlights(), 4),
                normalizeTextBlocks(raw.cases(), fallback.cases(), 3),
                normalizeStringList(raw.process(), fallback.process(), 5, 20),
                normalizePlans(raw.plans(), fallback.plans()),
                normalizeFaqs(raw.faqs(), fallback.faqs()),
                normalizeContact(raw.contact(), fallback.contact())
        );
    }

    /** 规范化{@code Constraints}。 */
    private CreateSpec.Constraints normalizeConstraints(CreateSpec.Constraints raw) {
        if (raw == null) {
            raw = new CreateSpec.Constraints(true, List.of(), List.of(), MAX_ENTITIES, MAX_FIELDS);
        }
        return new CreateSpec.Constraints(
                true,
                normalizeStringList(raw.protectedFiles(), List.of("package.json", "go.mod"), 12, 80),
                normalizeStringList(raw.securityRules(), List.of("no_script_html", "no_secret", "no_private_endpoint"), 12, 64),
                clamp(raw.maxEntities(), 1, MAX_ENTITIES, MAX_ENTITIES),
                clamp(raw.maxFieldsPerEntity(), 2, MAX_FIELDS, MAX_FIELDS)
        );
    }

    /** 规范化{@code Modules}。 */
    private List<CreateSpec.ModuleSpec> normalizeModules(List<CreateSpec.ModuleSpec> raw, List<CreateSpec.ModuleSpec> fallback) {
        List<CreateSpec.ModuleSpec> source = raw == null || raw.isEmpty() ? fallback : raw;
        return source.stream()
                .filter(module -> module != null)
                .limit(8)
                .map(module -> new CreateSpec.ModuleSpec(
                        lowerIdentifier(module.id(), "module"),
                        safe(module.label(), 24, module.id()),
                        normalizeStringList(module.capabilities(), List.of(), 8, 32)
                ))
                .toList();
    }

    /** 规范化{@code Keywords}。 */
    private List<String> normalizeKeywords(List<String> raw, List<String> fallback, int maxItems) {
        List<String> values = normalizeStringList(raw, fallback, maxItems, 24);
        List<String> mapped = new ArrayList<>();
        for (String value : values) {
            String normalized = switch (value) {
                case "高级" -> "premium";
                case "年轻" -> "youthful";
                case "科技" -> "tech";
                case "极简" -> "minimal";
                case "运营中台" -> "ops_dashboard";
                case "医疗可信" -> "medical_trust";
                case "教育温暖" -> "education_warm";
                default -> value;
            };
            if (!mapped.contains(normalized)) {
                mapped.add(normalized);
            }
        }
        return mapped;
    }

    /** 规范化{@code String}列表。 */
    private List<String> normalizeStringList(List<String> raw, List<String> fallback, int maxItems, int maxLength) {
        List<String> result = new ArrayList<>();
        if (raw != null) {
            for (String value : raw) {
                String safe = safe(value, maxLength, "");
                if (StrUtil.isNotBlank(safe) && !result.contains(safe) && result.size() < maxItems) {
                    result.add(safe);
                }
            }
        }
        if (fallback != null) {
            for (String value : fallback) {
                String safe = safe(value, maxLength, "");
                if (StrUtil.isNotBlank(safe) && !result.contains(safe) && result.size() < maxItems) {
                    result.add(safe);
                }
            }
        }
        return result;
    }

    /** 规范化统计。 */
    private List<CreateSpec.Stat> normalizeStats(List<CreateSpec.Stat> raw, List<CreateSpec.Stat> fallback) {
        List<CreateSpec.Stat> result = new ArrayList<>();
        for (CreateSpec.Stat item : raw == null ? List.<CreateSpec.Stat>of() : raw) {
            if (item != null && result.size() < 3) {
                result.add(new CreateSpec.Stat(safe(item.value(), 16, ""), safe(item.label(), 20, "")));
            }
        }
        for (CreateSpec.Stat item : fallback) {
            if (result.size() >= 3) break;
            result.add(item);
        }
        return result;
    }

    /** 规范化{@code Text}{@code Blocks}。 */
    private List<CreateSpec.TextBlock> normalizeTextBlocks(List<CreateSpec.TextBlock> raw,
                                                           List<CreateSpec.TextBlock> fallback,
                                                           int maxItems) {
        List<CreateSpec.TextBlock> result = new ArrayList<>();
        for (CreateSpec.TextBlock item : raw == null ? List.<CreateSpec.TextBlock>of() : raw) {
            if (item != null && result.size() < maxItems) {
                result.add(new CreateSpec.TextBlock(safe(item.title(), 24, ""), safe(item.text(), 100, "")));
            }
        }
        for (CreateSpec.TextBlock item : fallback) {
            if (result.size() >= maxItems) break;
            result.add(item);
        }
        return result;
    }

    /** 规范化{@code Plans}。 */
    private List<CreateSpec.Plan> normalizePlans(List<CreateSpec.Plan> raw, List<CreateSpec.Plan> fallback) {
        List<CreateSpec.Plan> result = new ArrayList<>();
        for (CreateSpec.Plan item : raw == null ? List.<CreateSpec.Plan>of() : raw) {
            if (item != null && result.size() < 3) {
                result.add(new CreateSpec.Plan(
                        safe(item.name(), 20, ""),
                        safe(item.price(), 20, ""),
                        safe(item.desc(), 80, ""),
                        normalizeStringList(item.features(), List.of("核心功能", "上线指导"), 4, 24)
                ));
            }
        }
        for (CreateSpec.Plan item : fallback) {
            if (result.size() >= 3) break;
            result.add(item);
        }
        return result;
    }

    /** 规范化{@code Faqs}。 */
    private List<CreateSpec.Faq> normalizeFaqs(List<CreateSpec.Faq> raw, List<CreateSpec.Faq> fallback) {
        List<CreateSpec.Faq> result = new ArrayList<>();
        for (CreateSpec.Faq item : raw == null ? List.<CreateSpec.Faq>of() : raw) {
            if (item != null && result.size() < 4) {
                result.add(new CreateSpec.Faq(safe(item.q(), 40, ""), safe(item.a(), 120, "")));
            }
        }
        for (CreateSpec.Faq item : fallback) {
            if (result.size() >= 4) break;
            result.add(item);
        }
        return result;
    }

    private CreateSpec.Contact normalizeContact(CreateSpec.Contact raw, CreateSpec.Contact fallback) {
        if (raw == null) return fallback;
        return new CreateSpec.Contact(
                safe(raw.email(), 64, fallback.email()),
                safe(raw.phone(), 32, fallback.phone()),
                safe(raw.address(), 64, fallback.address())
        );
    }

    private String safe(String value, int maxLength, String fallback) {
        return sanitizer.text(value, maxLength, fallback);
    }

    private String hex(String value, String fallback) {
        String safe = safe(value, 16, fallback);
        return safe.matches("^#[0-9a-fA-F]{6}$") ? safe : fallback;
    }

    /** 响应{@code e}{@code Of}事件。 */
    private String oneOf(String value, String fallback, String... allowed) {
        for (String item : allowed) {
            if (item.equals(value)) {
                return value;
            }
        }
        return fallback;
    }

    private String identifierLike(String value, String fallback) {
        String cleaned = safe(value, 48, fallback).replace('-', '_').replace(' ', '_').toLowerCase(Locale.ROOT);
        cleaned = IDENTIFIER_CLEANUP.matcher(cleaned).replaceAll("_").replaceAll("_+", "_");
        return StrUtil.isBlank(cleaned) ? fallback : cleaned;
    }

    private String lowerIdentifier(String value, String fallback) {
        String mapped = CN_FIELD_NAMES.getOrDefault(StrUtil.blankToDefault(value, ""), value);
        String pascal = pascalIdentifier(mapped, fallback);
        return pascal.substring(0, 1).toLowerCase(Locale.ROOT) + pascal.substring(1);
    }

    /** 返回{@code pascal}{@code Identifier}。 */
    private String pascalIdentifier(String value, String fallback) {
        String cleaned = identifierLike(value, fallback);
        StringBuilder result = new StringBuilder();
        for (String part : cleaned.split("_+")) {
            if (StrUtil.isBlank(part)) continue;
            result.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                result.append(part.substring(1));
            }
        }
        if (result.isEmpty()) {
            result.append(fallback);
        }
        if (Character.isDigit(result.charAt(0))) {
            result.insert(0, "N");
        }
        return result.toString();
    }

    private String snake(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    public record NormalizedSpec(CreateSpec spec, CreateSpecValidationResult validation) {
    }
}
