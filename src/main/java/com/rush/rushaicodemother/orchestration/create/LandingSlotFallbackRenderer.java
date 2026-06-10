package com.rush.rushaicodemother.orchestration.create;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class LandingSlotFallbackRenderer {

    private static final String LANDING_TEMPLATE = "vue-web-landing";
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

    public LandingFallback fallback(String userMessage, SlotGroup group, String reason) {
        if (!supports(group)) {
            return LandingFallback.empty();
        }
        String industry = inferIndustry(userMessage);
        String brandName = inferBrandName(userMessage, industry);
        String headline = headline(userMessage, industry);
        String description = description(userMessage, industry);
        String content = renderLandingData(brandName, headline, description, industry);
        return new LandingFallback(
                group.slotIds(),
                List.of(PatchOperation.modify("src/data/landingData.ts", content)),
                content.length(),
                "AI slot 填充超时，已使用本地 landing 数据生成器兜底：" + StrUtil.blankToDefault(reason, "slot_fill_failed")
        );
    }

    private String renderLandingData(String brandName, String headline, String description, String industry) {
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

                export const brand: LandingBrand = {
                  name: '%s',
                  headline: '%s',
                  description: '%s',
                  cta: '预约咨询',
                  secondary: '查看案例'
                }

                export const nav = ['亮点', '案例', '流程', '价格', 'FAQ']

                export const stats: Stat[] = [
                  { value: '300+', label: '服务客户' },
                  { value: '98%%', label: '交付满意度' },
                  { value: '7天', label: '最快上线' }
                ]

                export const highlights: Highlight[] = [
                  { title: '品牌形象升级', text: '围绕%s的核心优势，建立清晰可信的官网表达。' },
                  { title: '服务产品展示', text: '用结构化区块呈现产品、服务、案例和转化路径。' },
                  { title: '线索转化设计', text: '从首屏 CTA 到咨询入口，降低用户决策成本。' },
                  { title: '移动端友好', text: '适配手机、平板和桌面访问，保持稳定阅读体验。' }
                ]

                export const cases: Case[] = [
                  { title: '%s官网升级', text: '重构品牌叙事和服务展示，提升咨询转化效率。' },
                  { title: '产品服务矩阵', text: '把复杂业务拆解为清晰模块，帮助访客快速理解价值。' },
                  { title: '客户案例展示', text: '用结果数据和真实场景增强信任感。' }
                ]

                export const process = ['需求诊断', '方案设计', '内容搭建', '上线优化']

                export const plans: Plan[] = [
                  { name: '标准官网', price: '¥9,800 起', desc: '适合品牌展示和基础获客。', features: ['首页搭建', '服务展示', '联系表单'] },
                  { name: '增长官网', price: '¥29,800 起', desc: '适合多产品线和案例沉淀。', features: ['多页面结构', '案例模块', 'SEO 基础优化'] },
                  { name: '定制方案', price: '按需报价', desc: '适合复杂业务和系统集成。', features: ['多语言扩展', '在线客服', '数据接入'] }
                ]

                export const faqs: FAQ[] = [
                  { q: '多久可以上线？', a: '标准官网通常 1-2 周可上线，复杂内容和系统集成会按需求评估。' },
                  { q: '是否支持多语言？', a: '模板预留了多语言扩展空间，首次生成先保证中文官网稳定可运行。' },
                  { q: '是否支持在线客服？', a: '可以接入第三方客服或表单线索系统，首次生成提供清晰的咨询入口。' },
                  { q: '后续还能继续修改吗？', a: '可以。首次生成完成后，编辑模式会基于现有代码精准修改页面、数据和交互。' }
                ]

                export const contact: Contact = {
                  email: 'contact@example.com',
                  phone: '400-000-0000',
                  address: '商务中心 · 线上咨询可预约'
                }
                """.formatted(
                escape(brandName),
                escape(headline),
                escape(description),
                escape(industry),
                escape(industry)
        );
    }

    private String inferIndustry(String userMessage) {
        String normalized = StrUtil.blankToDefault(userMessage, "").toLowerCase(Locale.ROOT);
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

    public record LandingFallback(
            List<String> filledSlots,
            List<PatchOperation> patchOperations,
            int totalChars,
            String summary
    ) {
        private static LandingFallback empty() {
            return new LandingFallback(List.of(), List.of(), 0, "");
        }

        public boolean available() {
            return patchOperations != null && !patchOperations.isEmpty();
        }
    }
}
