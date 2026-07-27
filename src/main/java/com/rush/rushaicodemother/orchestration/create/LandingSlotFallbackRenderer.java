package com.rush.rushaicodemother.orchestration.create;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 落地页插槽回退渲染器。
 */
@Component
public class LandingSlotFallbackRenderer {

    private static final String LANDING_TEMPLATE = "vue-web-landing";
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final List<String> LANDING_DATA_SLOTS = List.of(
            "landing_core_data",
            "brand_data",
            "highlights_data",
            "cases_data",
            "faq_data",
            "pricing_data",
            "contact_info"
    );

    public boolean supports(SlotGroup group) {
        if (group == null || !LANDING_TEMPLATE.equals(group.templateId())) {
            return false;
        }
        return group.slotIds().stream().allMatch(LANDING_DATA_SLOTS::contains);
    }

    public LandingFallback renderFromSpec(String userMessage,
                                          SlotGroup group,
                                          CreateSpec spec,
                                          String reason) {
        if (!supports(group)) {
            return LandingFallback.empty();
        }
        LandingRecipe recipe = toRecipe(userMessage, spec);
        String content = renderLandingData(recipe);
        return new LandingFallback(
                group.slotIds(),
                List.of(PatchOperation.modify("src/data/landingData.ts", content)),
                content.length(),
                "AI CREATE 规格已应用，本地 recipe 已生成 landing 数据：" + StrUtil.blankToDefault(reason, "create_spec_applied")
        );
    }

    public LandingFallback fallback(String userMessage, SlotGroup group, String reason) {
        if (!supports(group)) {
            return LandingFallback.empty();
        }
        LandingRecipe recipe = localRecipe(userMessage);
        String content = renderLandingData(recipe);
        return new LandingFallback(
                group.slotIds(),
                List.of(PatchOperation.modify("src/data/landingData.ts", content)),
                content.length(),
                "AI CREATE spec 生成失败，已使用本地 landing 数据生成器兜底：" + StrUtil.blankToDefault(reason, "create_spec_failed")
        );
    }

    private LandingRecipe toRecipe(String userMessage, CreateSpec spec) {
        LandingRecipe fallback = localRecipe(userMessage);
        if (spec == null) {
            return fallback;
        }
        CreateSpec.Product product = spec.product();
        CreateSpec.Theme theme = spec.frontend() == null ? null : spec.frontend().theme();
        CreateSpec.Landing landing = spec.content() == null ? null : spec.content().landing();
        String industry = StrUtil.blankToDefault(readableDomain(product == null ? null : product.domain()), fallback.industry());
        return new LandingRecipe(
                firstNonBlank(product == null ? null : product.brandName(), fallback.brandName()),
                firstNonBlank(landing == null ? null : landing.headline(), fallback.headline()),
                firstNonBlank(landing == null ? null : landing.description(), fallback.description()),
                firstNonBlank(landing == null ? null : landing.cta(), fallback.cta()),
                firstNonBlank(landing == null ? null : landing.secondary(), fallback.secondary()),
                industry,
                validHex(theme == null ? null : theme.primary(), fallback.primary()),
                validHex(theme == null ? null : theme.accent(), fallback.accent()),
                normalizeNav(landing == null ? null : landing.nav(), fallback.nav()),
                normalizeStats(landing == null ? null : landing.stats(), fallback.stats()),
                normalizeTextBlocks(landing == null ? null : landing.highlights(), fallback.highlights(), 4),
                normalizeTextBlocks(landing == null ? null : landing.cases(), fallback.cases(), 3),
                normalizeStringList(landing == null ? null : landing.process(), fallback.process(), 4),
                normalizePlans(landing == null ? null : landing.plans(), fallback.plans()),
                normalizeFaqs(landing == null ? null : landing.faqs(), fallback.faqs()),
                normalizeContact(landing == null ? null : landing.contact(), fallback.contact())
        );
    }

    private LandingRecipe localRecipe(String userMessage) {
        String industry = inferIndustry(userMessage);
        String brandName = inferBrandName(userMessage, industry);
        String headline = headline(userMessage, industry);
        String description = description(userMessage, industry);
        return new LandingRecipe(
                brandName,
                headline,
                description,
                "预约咨询",
                "查看案例",
                industry,
                "#2563eb",
                "#f97316",
                List.of("亮点", "案例", "流程", "价格", "FAQ"),
                List.of(
                        new Stat("300+", "服务客户"),
                        new Stat("98%", "交付满意度"),
                        new Stat("7天", "最快上线")
                ),
                List.of(
                        new TextBlock("品牌形象升级", "围绕" + industry + "的核心优势，建立清晰可信的官网表达。"),
                        new TextBlock("服务产品展示", "用结构化区块呈现产品、服务、案例和转化路径。"),
                        new TextBlock("线索转化设计", "从首屏 CTA 到咨询入口，降低用户决策成本。"),
                        new TextBlock("移动端友好", "适配手机、平板和桌面访问，保持稳定阅读体验。")
                ),
                List.of(
                        new TextBlock(industry + "官网升级", "重构品牌叙事和服务展示，提升咨询转化效率。"),
                        new TextBlock("产品服务矩阵", "把复杂业务拆解为清晰模块，帮助访客快速理解价值。"),
                        new TextBlock("客户案例展示", "用结果数据和真实场景增强信任感。")
                ),
                List.of("需求诊断", "方案设计", "内容搭建", "上线优化"),
                List.of(
                        new Plan("标准官网", "¥9,800 起", "适合品牌展示和基础获客。", List.of("首页搭建", "服务展示", "联系表单")),
                        new Plan("增长官网", "¥29,800 起", "适合多产品线和案例沉淀。", List.of("多页面结构", "案例模块", "SEO 基础优化")),
                        new Plan("定制方案", "按需报价", "适合复杂业务和系统集成。", List.of("多语言扩展", "在线客服", "数据接入"))
                ),
                List.of(
                        new Faq("多久可以上线？", "标准官网通常 1-2 周可上线，复杂内容和系统集成会按需求评估。"),
                        new Faq("是否支持多语言？", "模板预留了多语言扩展空间，首次生成先保证中文官网稳定可运行。"),
                        new Faq("是否支持在线客服？", "可以接入第三方客服或表单线索系统，首次生成提供清晰的咨询入口。"),
                        new Faq("后续还能继续修改吗？", "可以。首次生成完成后，编辑模式会基于现有代码精准修改页面、数据和交互。")
                ),
                new Contact("contact@example.com", "400-000-0000", "商务中心 · 线上咨询可预约")
        );
    }

    private String renderLandingData(LandingRecipe recipe) {
        return """
                export interface LandingBrand {
                  name: string
                  headline: string
                  description: string
                  cta: string
                  secondary: string
                }

                export interface Stat {
                  value: string
                  label: string
                }

                export interface Highlight {
                  title: string
                  text: string
                }

                export interface Case {
                  title: string
                  text: string
                }

                export interface Plan {
                  name: string
                  price: string
                  desc: string
                  features: string[]
                }

                export interface FAQ {
                  q: string
                  a: string
                }

                export interface Contact {
                  email: string
                  phone: string
                  address: string
                }

                export const theme = {
                  primary: '%s',
                  accent: '%s'
                }

                export const brand: LandingBrand = {
                  name: '%s',
                  headline: '%s',
                  description: '%s',
                  cta: '%s',
                  secondary: '%s'
                }

                export const nav = %s

                export const stats: Stat[] = [
                %s
                ]

                export const highlights: Highlight[] = [
                %s
                ]

                export const cases: Case[] = [
                %s
                ]

                export const process = %s

                export const plans: Plan[] = [
                %s
                ]

                export const faqs: FAQ[] = [
                %s
                ]

                export const contact: Contact = {
                  email: '%s',
                  phone: '%s',
                  address: '%s'
                }
                """.formatted(
                escape(recipe.primary()),
                escape(recipe.accent()),
                escape(recipe.brandName()),
                escape(recipe.headline()),
                escape(recipe.description()),
                escape(recipe.cta()),
                escape(recipe.secondary()),
                stringArray(recipe.nav()),
                statsArray(recipe.stats()),
                textBlockArray(recipe.highlights()),
                textBlockArray(recipe.cases()),
                stringArray(recipe.process()),
                planArray(recipe.plans()),
                faqArray(recipe.faqs()),
                escape(recipe.contact().email()),
                escape(recipe.contact().phone()),
                escape(recipe.contact().address())
        );
    }

    private String inferIndustry(String userMessage) {
        String normalized = StrUtil.blankToDefault(userMessage, "").toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "健身", "私教", "瑜伽", "运动", "课程")) {
            return "健身服务";
        }
        if (containsAny(normalized, "科技", "软件", "saas", "ai", "智能")) {
            return "科技企业";
        }
        if (containsAny(normalized, "教育", "课程", "培训")) {
            return "教育品牌";
        }
        if (containsAny(normalized, "医疗", "健康", "诊所")) {
            return "健康服务";
        }
        if (containsAny(normalized, "制造", "工业", "设备")) {
            return "制造企业";
        }
        if (containsAny(normalized, "咨询", "服务", "企业官网", "公司")) {
            return "专业服务";
        }
        return "企业品牌";
    }

    private String inferBrandName(String userMessage, String industry) {
        if (StrUtil.isBlank(userMessage)) {
            return industry + "官网";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:品牌|公司|企业|官网)[名名称为叫：: ]+([\\p{IsHan}A-Za-z0-9]{2,16})")
                .matcher(userMessage);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return switch (industry) {
            case "科技企业" -> "智启科技";
            case "健身服务" -> "FitPilot";
            case "教育品牌" -> "知行教育";
            case "健康服务" -> "安心健康";
            case "制造企业" -> "精工制造";
            case "专业服务" -> "远见咨询";
            default -> "新域企业";
        };
    }

    private String headline(String userMessage, String industry) {
        if (containsAny(userMessage, "专业", "商务")) {
            return "打造专业可信的" + industry + "官网";
        }
        if (containsAny(userMessage, "产品", "服务")) {
            return "清晰展示产品服务，沉淀品牌信任";
        }
        return "让访客快速理解你的价值";
    }

    private String description(String userMessage, String industry) {
        if (containsAny(userMessage, "新闻", "资讯")) {
            return "包含公司介绍、产品服务、新闻资讯、客户案例和联系入口，适合" + industry + "对外展示与获客。";
        }
        return "以品牌介绍、服务展示、案例证明和咨询转化为核心，帮助" + industry + "建立稳定的线上门面。";
    }

    private boolean containsAny(String value, String... keywords) {
        String normalized = StrUtil.blankToDefault(value, "").toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String escape(String value) {
        return StrUtil.blankToDefault(value, "").replace("\\", "\\\\").replace("'", "\\'");
    }

    private String firstNonBlank(String value, String fallback) {
        return StrUtil.isBlank(value) ? fallback : value.strip();
    }

    private String validHex(String value, String fallback) {
        String normalized = StrUtil.blankToDefault(value, "").strip();
        return HEX_COLOR_PATTERN.matcher(normalized).matches() ? normalized : fallback;
    }

    private String readableDomain(String domain) {
        String normalized = StrUtil.blankToDefault(domain, "").replace('_', ' ').strip();
        if (StrUtil.isBlank(normalized)) {
            return "";
        }
        return normalized;
    }

    private List<String> normalizeNav(List<String> source, List<String> fallback) {
        List<String> values = normalizeStringList(source, fallback, 5);
        Set<String> required = Set.of("亮点", "案例", "流程", "价格", "FAQ");
        List<String> result = new ArrayList<>(values);
        for (String item : required) {
            if (!result.contains(item) && result.size() < 6) {
                result.add(item);
            }
        }
        return result;
    }

    private List<String> normalizeStringList(List<String> source, List<String> fallback, int targetSize) {
        List<String> values = new ArrayList<>();
        if (source != null) {
            for (String item : source) {
                if (StrUtil.isNotBlank(item) && values.size() < targetSize) {
                    values.add(item.strip());
                }
            }
        }
        for (String item : fallback) {
            if (values.size() >= targetSize) {
                break;
            }
            if (StrUtil.isNotBlank(item) && !values.contains(item)) {
                values.add(item);
            }
        }
        return values;
    }

    private List<Stat> normalizeStats(List<CreateSpec.Stat> source, List<Stat> fallback) {
        List<Stat> values = new ArrayList<>();
        if (source != null) {
            for (CreateSpec.Stat item : source) {
                if (item != null && StrUtil.isNotBlank(item.value()) && StrUtil.isNotBlank(item.label()) && values.size() < 3) {
                    values.add(new Stat(item.value().strip(), item.label().strip()));
                }
            }
        }
        for (Stat item : fallback) {
            if (values.size() >= 3) {
                break;
            }
            values.add(item);
        }
        return values;
    }

    private List<TextBlock> normalizeTextBlocks(List<CreateSpec.TextBlock> source,
                                                List<TextBlock> fallback,
                                                int targetSize) {
        List<TextBlock> values = new ArrayList<>();
        if (source != null) {
            for (CreateSpec.TextBlock item : source) {
                if (item != null && StrUtil.isNotBlank(item.title()) && StrUtil.isNotBlank(item.text())
                        && values.size() < targetSize) {
                    values.add(new TextBlock(item.title().strip(), item.text().strip()));
                }
            }
        }
        for (TextBlock item : fallback) {
            if (values.size() >= targetSize) {
                break;
            }
            values.add(item);
        }
        return values;
    }

    private List<Plan> normalizePlans(List<CreateSpec.Plan> source, List<Plan> fallback) {
        List<Plan> values = new ArrayList<>();
        if (source != null) {
            for (CreateSpec.Plan item : source) {
                if (item != null && StrUtil.isNotBlank(item.name()) && StrUtil.isNotBlank(item.price())
                        && StrUtil.isNotBlank(item.desc()) && values.size() < 3) {
                    values.add(new Plan(
                            item.name().strip(),
                            item.price().strip(),
                            item.desc().strip(),
                            normalizeStringList(item.features(), List.of("核心功能", "基础支持", "上线指导"), 4)
                    ));
                }
            }
        }
        for (Plan item : fallback) {
            if (values.size() >= 3) {
                break;
            }
            values.add(item);
        }
        return values;
    }

    private List<Faq> normalizeFaqs(List<CreateSpec.Faq> source, List<Faq> fallback) {
        List<Faq> values = new ArrayList<>();
        if (source != null) {
            for (CreateSpec.Faq item : source) {
                if (item != null && StrUtil.isNotBlank(item.q()) && StrUtil.isNotBlank(item.a())
                        && values.size() < 4) {
                    values.add(new Faq(item.q().strip(), item.a().strip()));
                }
            }
        }
        for (Faq item : fallback) {
            if (values.size() >= 4) {
                break;
            }
            values.add(item);
        }
        return values;
    }

    private Contact normalizeContact(CreateSpec.Contact source, Contact fallback) {
        if (source == null) {
            return fallback;
        }
        return new Contact(
                firstNonBlank(source.email(), fallback.email()),
                firstNonBlank(source.phone(), fallback.phone()),
                firstNonBlank(source.address(), fallback.address())
        );
    }

    private String stringArray(List<String> values) {
        return "[" + values.stream()
                .map(value -> "'" + escape(value) + "'")
                .collect(java.util.stream.Collectors.joining(", ")) + "]";
    }

    private String statsArray(List<Stat> values) {
        return values.stream()
                .map(item -> "  { value: '" + escape(item.value()) + "', label: '" + escape(item.label()) + "' }")
                .collect(java.util.stream.Collectors.joining(",\n"));
    }

    private String textBlockArray(List<TextBlock> values) {
        return values.stream()
                .map(item -> "  { title: '" + escape(item.title()) + "', text: '" + escape(item.text()) + "' }")
                .collect(java.util.stream.Collectors.joining(",\n"));
    }

    private String planArray(List<Plan> values) {
        return values.stream()
                .map(item -> "  { name: '" + escape(item.name()) + "', price: '" + escape(item.price())
                        + "', desc: '" + escape(item.desc()) + "', features: " + stringArray(item.features()) + " }")
                .collect(java.util.stream.Collectors.joining(",\n"));
    }

    private String faqArray(List<Faq> values) {
        return values.stream()
                .map(item -> "  { q: '" + escape(item.q()) + "', a: '" + escape(item.a()) + "' }")
                .collect(java.util.stream.Collectors.joining(",\n"));
    }

    private record LandingRecipe(
            String brandName,
            String headline,
            String description,
            String cta,
            String secondary,
            String industry,
            String primary,
            String accent,
            List<String> nav,
            List<Stat> stats,
            List<TextBlock> highlights,
            List<TextBlock> cases,
            List<String> process,
            List<Plan> plans,
            List<Faq> faqs,
            Contact contact
    ) {
    }

    private record Stat(String value, String label) {
    }

    private record TextBlock(String title, String text) {
    }

    private record Plan(String name, String price, String desc, List<String> features) {
    }

    private record Faq(String q, String a) {
    }

    private record Contact(String email, String phone, String address) {
    }

    public record LandingFallback(
            List<String> filledSlots,
            List<PatchOperation> patchOperations,
            int totalChars,
            String summary
    ) {
        public static LandingFallback empty() {
            return new LandingFallback(List.of(), List.of(), 0, "");
        }

        public boolean available() {
            return patchOperations != null && !patchOperations.isEmpty();
        }
    }
}
