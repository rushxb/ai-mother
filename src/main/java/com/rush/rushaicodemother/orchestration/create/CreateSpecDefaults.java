package com.rush.rushaicodemother.orchestration.create;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.CreateSpec;

import java.util.List;
import java.util.Locale;

/**
 * 当快速 AI 规范模型不可用时，使用本地 CREATE 规范回退。
 */
public class CreateSpecDefaults {

    /**
 * 根据输入数据创建当前对象。
 *
 * @param userMessage 用户消息
 * @param plan 计划
 * @param group 分组
 * @param reason 原因
 * @return 创建{@code Spec}{@code Defaults}
 */
    public CreateSpec fromRequest(String userMessage, CreateGenerationPlan plan, SlotGroup group, String reason) {
        String message = StrUtil.blankToDefault(userMessage, "");
        String appType = appType(plan, group);
        String domain = domain(message);
        String brandName = brandName(message, domain);
        String entityName = entityName(message);
        String entityLabel = entityLabel(message);
        List<CreateSpec.FieldSpec> fields = fieldsFor(entityName, entityLabel);
        CreateSpec.EntitySpec primaryEntity = new CreateSpec.EntitySpec(
                entityName,
                entityLabel,
                fields,
                List.of(),
                List.of("list", "create", "update", "delete", "detail")
        );
        CreateSpec.Frontend frontend = new CreateSpec.Frontend(
                layout(appType),
                styleKeywords(domain, appType),
                density(appType),
                components(appType),
                interactions(appType),
                dataViz(appType),
                navigation(appType, entityLabel),
                new CreateSpec.Theme("#2563eb", "#f97316", "#f8fafc", "8px", "light")
        );
        CreateSpec.Backend backend = new CreateSpec.Backend(
                "rest",
                false,
                true,
                true,
                true,
                true,
                List.of("createdAt", "updatedAt"),
                false,
                true,
                List.of("required", "maxLength"),
                "standard_json",
                lowerFirst(entityName)
        );
        CreateSpec.Database database = new CreateSpec.Database(
                List.of(new CreateSpec.TableSpec(tableName(entityName), fields, List.of(fields.getFirst().name()))),
                List.of(fields.getFirst().name()),
                true,
                "append_sql_schema"
        );
        CreateSpec.Content content = new CreateSpec.Content(
                tone(domain),
                domain + "示例数据",
                navigation(appType, entityLabel),
                pages(appType, entityLabel),
                landing(brandName, domain)
        );
        CreateSpec.Constraints constraints = new CreateSpec.Constraints(
                true,
                List.of("package.json", "vite.config.ts", "go.mod"),
                List.of("no_script_html", "no_secret", "no_private_endpoint", StrUtil.blankToDefault(reason, "local_spec_fallback")),
                4,
                8
        );
        return new CreateSpec(
                new CreateSpec.Product(appType, domain, brandName, audience(domain), goal(appType, domain)),
                modules(appType, entityLabel),
                List.of(primaryEntity),
                frontend,
                backend,
                database,
                content,
                constraints
        );
    }

    /** 返回应用类型。 */
    private String appType(CreateGenerationPlan plan, SlotGroup group) {
        String templateId = group == null ? "" : StrUtil.blankToDefault(group.templateId(), "");
        if (templateId.contains("admin")) return "admin";
        if (templateId.contains("backend")) return "backend";
        if (templateId.contains("mobile")) return "mobile";
        if (templateId.contains("landing")) return "landing";
        if (plan != null && plan.codeGenType() != null) {
            return plan.codeGenType().getValue();
        }
        return "basic";
    }

    /** 返回{@code domain}。 */
    private String domain(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "健身", "私教", "瑜伽", "运动")) return "fitness_saas";
        if (containsAny(normalized, "教育", "课程", "培训", "学校")) return "education";
        if (containsAny(normalized, "商品", "订单", "库存", "电商", "零售")) return "retail";
        if (containsAny(normalized, "医疗", "健康", "诊所")) return "healthcare";
        if (containsAny(normalized, "客户", "crm", "销售")) return "crm";
        return "enterprise_service";
    }

    /** 返回品牌名称。 */
    private String brandName(String message, String domain) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:品牌|产品|系统|平台|应用|项目)[名名称为叫：: ]+([\\p{IsHan}A-Za-z0-9]{2,18})")
                .matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return switch (domain) {
            case "fitness_saas" -> "FitPilot";
            case "education" -> "知行云";
            case "retail" -> "商策云";
            case "healthcare" -> "安心云";
            case "crm" -> "客脉云";
            default -> "Nexa Studio";
        };
    }

    /** 返回{@code entity}名称。 */
    private String entityName(String message) {
        if (containsAny(message, "课程")) return "Course";
        if (containsAny(message, "会员")) return "Member";
        if (containsAny(message, "教练")) return "Coach";
        if (containsAny(message, "商品", "产品")) return "Product";
        if (containsAny(message, "订单")) return "Order";
        if (containsAny(message, "客户")) return "Customer";
        return "Record";
    }

    /** 返回{@code entity}{@code Label}。 */
    private String entityLabel(String message) {
        if (containsAny(message, "课程")) return "课程";
        if (containsAny(message, "会员")) return "会员";
        if (containsAny(message, "教练")) return "教练";
        if (containsAny(message, "商品", "产品")) return "商品";
        if (containsAny(message, "订单")) return "订单";
        if (containsAny(message, "客户")) return "客户";
        return "业务记录";
    }

    /** 返回{@code fields}{@code For}。 */
    private List<CreateSpec.FieldSpec> fieldsFor(String entityName, String entityLabel) {
        if ("Course".equals(entityName)) {
            return List.of(
                    field("title", "string", "课程名称", true),
                    field("coach", "string", "教练", true),
                    field("price", "decimal", "价格", false),
                    field("status", "enum", "状态", false, List.of("上架", "下架")),
                    field("capacity", "integer", "容量", false)
            );
        }
        if ("Product".equals(entityName)) {
            return List.of(field("name", "string", "商品名称", true), field("sku", "string", "SKU", true),
                    field("price", "decimal", "价格", false), field("stock", "integer", "库存", false),
                    field("status", "enum", "状态", false, List.of("在售", "下架")));
        }
        return List.of(field("name", "string", entityLabel + "名称", true), field("owner", "string", "负责人", false),
                field("status", "enum", "状态", false, List.of("启用", "停用")), field("remark", "string", "备注", false));
    }

    private CreateSpec.FieldSpec field(String name, String type, String label, boolean required) {
        return field(name, type, label, required, List.of());
    }

    private CreateSpec.FieldSpec field(String name, String type, String label, boolean required, List<String> options) {
        return new CreateSpec.FieldSpec(name, type, label, required, options);
    }

    /** 返回{@code modules}。 */
    private List<CreateSpec.ModuleSpec> modules(String appType, String entityLabel) {
        if ("admin".equals(appType)) {
            return List.of(new CreateSpec.ModuleSpec("dashboard", "工作台", List.of("metrics", "charts")),
                    new CreateSpec.ModuleSpec("crud", entityLabel + "管理", List.of("table", "form", "batch_actions")));
        }
        if ("backend".equals(appType)) {
            return List.of(new CreateSpec.ModuleSpec("crud_api", entityLabel + "接口", List.of("rest", "pagination", "search")));
        }
        return List.of(new CreateSpec.ModuleSpec("presentation", "展示", List.of("content", "conversion")));
    }

    private String layout(String appType) {
        return switch (appType) {
            case "admin" -> "sidebar_dashboard";
            case "mobile" -> "mobile_tabbar";
            case "landing" -> "landing_scroll";
            default -> "top_nav";
        };
    }

    private String density(String appType) {
        return "admin".equals(appType) || "backend".equals(appType) ? "compact" : "comfortable";
    }

    /** 返回{@code style}{@code Keywords}。 */
    private List<String> styleKeywords(String domain, String appType) {
        if ("healthcare".equals(domain)) return List.of("医疗可信", "清爽", "专业");
        if ("education".equals(domain)) return List.of("教育温暖", "亲和", "清晰");
        if ("admin".equals(appType)) return List.of("运营中台", "高信息密度", "专业");
        return List.of("专业", "现代", "转化清晰");
    }

    private List<String> components(String appType) {
        return switch (appType) {
            case "admin" -> List.of("metric_cards", "data_table", "drawer_form", "charts");
            case "mobile" -> List.of("tabbar", "cards", "list", "sheet");
            case "backend" -> List.of("crud_api", "pagination", "validation");
            default -> List.of("hero", "cards", "pricing", "faq");
        };
    }

    private List<String> interactions(String appType) {
        return "admin".equals(appType)
                ? List.of("filter", "pagination", "batch_actions", "detail_drawer")
                : List.of("cta", "anchor_navigation");
    }

    private List<String> dataViz(String appType) {
        return "admin".equals(appType) ? List.of("metric_cards", "trend", "ranking") : List.of("proof_stats");
    }

    private List<String> navigation(String appType, String entityLabel) {
        return "admin".equals(appType)
                ? List.of("工作台", entityLabel + "管理", "数据分析", "系统设置")
                : List.of("亮点", "案例", "流程", "价格", "FAQ");
    }

    private List<String> pages(String appType, String entityLabel) {
        return "admin".equals(appType)
                ? List.of("dashboard", lowerFirst(entityLabel) + "_crud")
                : List.of("landing", "pricing", "faq");
    }

    /** 返回{@code landing}。 */
    private CreateSpec.Landing landing(String brandName, String domain) {
        String domainText = readableDomain(domain);
        return new CreateSpec.Landing(
                brandName + "，让" + domainText + "增长更清晰",
                "围绕真实业务流程组织页面、数据和转化路径，帮助团队快速上线并持续优化。",
                "预约演示",
                "查看方案",
                List.of("亮点", "案例", "流程", "价格", "FAQ"),
                List.of(new CreateSpec.Stat("300+", "服务客户"), new CreateSpec.Stat("98%", "满意度"),
                        new CreateSpec.Stat("7天", "最快上线")),
                List.of(new CreateSpec.TextBlock("业务流程清晰", "把复杂服务拆成可理解、可转化的模块。"),
                        new CreateSpec.TextBlock("数据证明可信", "用指标、案例和流程增强访客信任。"),
                        new CreateSpec.TextBlock("移动端友好", "兼顾手机和桌面访问体验。"),
                        new CreateSpec.TextBlock("后续可编辑", "生成后可继续精准增强页面和交互。")),
                List.of(new CreateSpec.TextBlock(domainText + "标杆案例", "上线后咨询转化和运营效率显著提升。"),
                        new CreateSpec.TextBlock("多团队协作", "统一内容、数据和行动入口。"),
                        new CreateSpec.TextBlock("增长复盘", "持续追踪关键业务指标。")),
                List.of("需求诊断", "方案设计", "内容搭建", "上线优化"),
                List.of(new CreateSpec.Plan("标准版", "¥9,800 起", "适合快速上线。", List.of("首页搭建", "服务展示", "联系表单")),
                        new CreateSpec.Plan("增长版", "¥29,800 起", "适合多模块增长。", List.of("多页面结构", "案例模块", "数据看板")),
                        new CreateSpec.Plan("定制版", "按需报价", "适合复杂集成。", List.of("私有部署", "系统对接", "专属支持"))),
                List.of(new CreateSpec.Faq("多久可以上线？", "标准项目通常 1-2 周完成初始化。"),
                        new CreateSpec.Faq("后续可以修改吗？", "可以，生成后可以继续用编辑模式精准调整。"),
                        new CreateSpec.Faq("支持移动端吗？", "支持响应式访问，首屏和核心内容会适配手机。"),
                        new CreateSpec.Faq("能接入现有系统吗？", "可以按接口和数据结构继续扩展。")),
                new CreateSpec.Contact("contact@example.com", "400-000-0000", "线上咨询可预约")
        );
    }

    private String audience(String domain) {
        return switch (domain) {
            case "fitness_saas" -> "健身房运营人员";
            case "education" -> "教务和招生团队";
            case "retail" -> "零售运营团队";
            case "healthcare" -> "医疗健康服务团队";
            case "crm" -> "销售和客户成功团队";
            default -> "企业运营团队";
        };
    }

    private String goal(String appType, String domain) {
        return appType + " for " + readableDomain(domain);
    }

    private String tone(String domain) {
        return switch (domain) {
            case "education" -> "亲和清晰";
            case "healthcare" -> "专业可信";
            default -> "专业直接";
        };
    }

    private String readableDomain(String domain) {
        return StrUtil.blankToDefault(domain, "业务").replace('_', ' ');
    }

    private String tableName(String entityName) {
        return lowerFirst(entityName).replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT) + "s";
    }

    private String lowerFirst(String value) {
        if (StrUtil.isBlank(value)) {
            return "record";
        }
        return value.substring(0, 1).toLowerCase(Locale.ROOT) + value.substring(1);
    }

    /** 返回{@code contains}{@code Any}。 */
    private boolean containsAny(String value, String... keywords) {
        String normalized = StrUtil.blankToDefault(value, "").toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
