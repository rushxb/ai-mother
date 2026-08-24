package com.rush.rushaicodemother.orchestration.create;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.rush.rushaicodemother.orchestration.create.CreatePromptKeywordMatcher.containsAny;

/**
 * Vue 模板与功能模块规则的 deep module。
 *
 * <p>独立 Vue CREATE 和全栈前端共享同一套模板、模块及 slot 事实，避免两个 adapter
 * 复制规则后漂移。对外只暴露两种真实规划场景，模板细节全部保持在 module 内。</p>
 */
@Component
public class VueTemplateFeaturePlanner {

    /** 规划独立 Vue 项目的完整前端能力。 */
    VueTemplateFeaturePlan planStandalone(String userMessage) {
        String baseTemplate = selectBaseTemplate(userMessage);
        List<FeatureModuleManifest> modules = new ArrayList<>();
        modules.add(defaultFrontendModule(baseTemplate));
        if ("vue-web-landing".equals(baseTemplate)) {
            return new VueTemplateFeaturePlan(baseTemplate, modules);
        }
        if (containsAny(userMessage, "登录", "注册", "auth", "权限", "用户")) {
            modules.add(authModule(baseTemplate));
        }
        if (containsAny(userMessage, "crud", "表格", "列表", "管理", "商品", "产品", "订单", "库存", "客户")) {
            modules.add(crudModule(baseTemplate));
        }
        if (containsAny(userMessage, "商品", "产品", "product", "库存")) {
            modules.add(commerceModule(baseTemplate));
        }
        if (containsAny(userMessage, "预约", "排班", "日程", "课程", "门店", "到店", "booking", "schedule")) {
            modules.add(bookingModule(baseTemplate));
        }
        if (containsAny(userMessage, "博客", "文章", "内容", "资讯", "知识库", "blog", "post", "cms")) {
            modules.add(contentModule(baseTemplate));
        }
        if (containsAny(userMessage, "报表", "统计", "分析", "看板", "运营", "analytics", "report")) {
            modules.add(analyticsModule(baseTemplate));
        }
        if (containsAny(userMessage, "设置", "配置", "偏好", "setting", "profile")) {
            modules.add(settingsModule(baseTemplate));
        }
        if (containsAny(userMessage, "订单", "order", "交易", "付款", "下单")) {
            modules.add(orderModule(baseTemplate));
        }
        if (containsAny(userMessage, "用户管理", "会员管理", "角色", "权限管理", "user manage")) {
            modules.add(userManageModule(baseTemplate));
        }
        if (containsAny(userMessage, "搜索", "筛选", "过滤", "search", "filter")) {
            modules.add(searchModule(baseTemplate));
        }
        if (containsAny(userMessage, "图库", "作品", "gallery", "portfolio", "相册")) {
            modules.add(galleryModule(baseTemplate));
        }
        if (containsAny(userMessage, "联系", "咨询", "客服", "地图", "contact")) {
            modules.add(contactModule(baseTemplate));
        }
        if (containsAny(userMessage, "价格", "方案", "pricing", "套餐", "定价")) {
            modules.add(pricingModule(baseTemplate));
        }
        if (containsAny(userMessage, "评价", "口碑", "客户见证", "testimonial", "review")) {
            modules.add(testimonialModule(baseTemplate));
        }
        if (containsAny(userMessage, "功能", "特性", "优势", "feature", "亮点")) {
            modules.add(featureModule(baseTemplate));
        }
        if (containsAny(userMessage, "分类", "类别", "category", "归档")) {
            modules.add(categoryModule(baseTemplate));
        }
        if (containsAny(userMessage, "导入", "导出", "批量", "import", "export", "csv", "excel")) {
            modules.add(exportModule(baseTemplate));
        }
        return new VueTemplateFeaturePlan(baseTemplate, modules);
    }

    /** 规划全栈项目中与后端 CRUD 契约配套的前端能力。 */
    VueTemplateFeaturePlan planFullStackFrontend(String userMessage) {
        String baseTemplate = selectBaseTemplate(userMessage);
        List<FeatureModuleManifest> modules = new ArrayList<>();
        modules.add(defaultFrontendModule(baseTemplate));
        modules.add(frontendCrudAdminModule(baseTemplate));
        modules.add(fullStackCrudApiModule(baseTemplate));
        if (containsAny(userMessage, "登录", "注册", "auth", "权限", "用户")) {
            modules.add(authModule(baseTemplate));
        }
        if (containsAny(userMessage, "商品", "产品", "product", "库存")) {
            modules.add(commerceModule(baseTemplate));
        }
        if (containsAny(userMessage, "预约", "排班", "日程", "课程", "门店", "到店", "booking", "schedule")) {
            modules.add(bookingModule(baseTemplate));
        }
        return new VueTemplateFeaturePlan(baseTemplate, modules);
    }

    private String selectBaseTemplate(String userMessage) {
        if (containsAny(userMessage, "移动端", "手机", "h5", "mobile", "商城", "会员", "预约", "vant")) {
            return "vue-web-mobile";
        }
        if (containsAny(userMessage, "后台", "管理", "admin", "dashboard", "仪表盘", "工作台", "表格", "crud")) {
            return "vue-web-admin";
        }
        if (containsAny(userMessage, "官网", "落地页", "landing", "活动页", "营销", "展示", "产品介绍", "宣传")) {
            return "vue-web-landing";
        }
        return "vue-web-basic";
    }

    private FeatureModuleManifest defaultFrontendModule(String templateId) {
        return switch (templateId) {
            case "vue-web-admin" -> module("admin-dashboard", "后台工作台", templateId,
                    List.of("dashboard_content", "mock_data", "statistics_cards", "operations_data", "activity_timeline"),
                    "管理类应用默认需要指标、运营数据和活动时间线");
            case "vue-web-mobile" -> module("mobile-home", "移动端首页", templateId,
                    List.of("home_content", "mock_data", "tabbar_config", "mobile_campaigns"),
                    "移动端应用默认需要首页、导航和活动数据");
            case "vue-web-landing" -> module("landing-page", "展示落地页", templateId,
                    List.of("landing_core_data"),
                    "展示型应用首次生成只让 AI 填业务数据，页面结构由模板保证");
            default -> module("basic-home", "通用首页", templateId,
                    List.of("home_content", "mock_data", "app_config", "navigation_items", "theme_tokens"),
                    "通用应用默认需要首页、数据、导航和主题");
        };
    }

    private FeatureModuleManifest authModule(String templateId) {
        return switch (templateId) {
            case "vue-web-mobile" -> module("mobile-auth-profile", "移动端用户中心", templateId,
                    List.of("user_store", "profile_page", "member_benefits"),
                    "移动端登录通常需要用户中心和会员权益");
            case "vue-web-landing" -> module("landing-lead-capture", "线索留资", templateId,
                    List.of("lead_form_data", "contact_info"),
                    "展示型登录/注册诉求通常表现为线索收集");
            case "vue-web-basic" -> module("basic-auth", "登录注册", templateId,
                    List.of("user_store", "login_page"),
                    "用户需求涉及登录或权限");
            default -> module("auth-login-register", "登录注册", templateId,
                    List.of("user_store", "login_page", "sidebar_menu"),
                    "用户需求涉及登录或权限");
        };
    }

    private FeatureModuleManifest crudModule(String templateId) {
        return switch (templateId) {
            case "vue-web-mobile" -> module("mobile-list-detail", "移动端列表详情", templateId,
                    List.of("category_page", "product_card", "product_list", "filter_config"),
                    "移动端 CRUD 更适合列表、详情和筛选入口");
            case "vue-web-landing" -> module("landing-case-library", "案例内容库", templateId,
                    List.of("case_studies", "faq_content", "comparison_data"),
                    "展示型 CRUD 以案例、问答和对比内容呈现");
            case "vue-web-basic" -> module("basic-crud-flow", "通用列表表单", templateId,
                    List.of("detail_page", "search_bar", "form_modal", "pro_table"),
                    "通用应用需要列表、详情、搜索和表单能力");
            default -> module("crud-table-form", "CRUD 表格表单", templateId,
                    prepend("table_columns", AdminRecipeCapability.crudSlotIds()),
                    "用户需求涉及管理后台 CRUD");
        };
    }

    private FeatureModuleManifest frontendCrudAdminModule(String templateId) {
        FeatureModuleManifest crud = crudModule(templateId);
        return module("frontend-crud-admin", "全栈前端 CRUD 管理", templateId, crud.slotIds(),
                "全栈 CRUD 需要前端管理页面、搜索表格和表单与后端 API 对齐");
    }

    /**
     * 冻结全栈前端必须消费后端 CRUD 契约的事实。
     *
     * <p>该 slot 只在 FULL_STACK 规划入口出现。尚未实现契约 adapter 的前端模板会安全降级
     * 到 Heavy，而不是继续发布一个前后端彼此断开的“伪全栈”项目。</p>
     */
    private FeatureModuleManifest fullStackCrudApiModule(String templateId) {
        return module(
                "full-stack-crud-bridge",
                "全栈 CRUD API 桥接",
                templateId,
                List.of("full_stack_crud_api"),
                "全栈前端必须通过与后端同源的 CRUD 契约完成查询和持久化"
        );
    }

    private FeatureModuleManifest commerceModule(String templateId) {
        return switch (templateId) {
            case "vue-web-mobile" -> module("mobile-commerce", "移动电商", templateId,
                    List.of("product_list", "cart_store", "orders_page", "category_page", "coupon_data"),
                    "移动电商需要商品、购物车、订单、分类和优惠信息");
            case "vue-web-landing" -> module("landing-product-offer", "产品报价展示", templateId,
                    List.of("pricing_packages", "case_studies", "comparison_data"),
                    "产品展示需要价格、案例和方案对比");
            case "vue-web-basic" -> module("basic-commerce", "商品展示", templateId,
                    List.of("mock_data", "detail_page", "pro_table"),
                    "商品应用需要商品数据、详情和列表");
            default -> module("product-management", "商品管理", templateId,
                    prepend(List.of("mock_data", "table_columns"), AdminRecipeCapability.commerceSlotIds()),
                    "商品管理需要商品、库存和批量操作");
        };
    }

    private FeatureModuleManifest bookingModule(String templateId) {
        return switch (templateId) {
            case "vue-web-mobile" -> module("mobile-booking", "移动预约", templateId,
                    List.of("booking_data", "drawer_example", "profile_page"),
                    "移动预约需要服务、时间段和底部抽屉交互");
            case "vue-web-landing" -> module("landing-booking", "预约转化", templateId,
                    List.of("lead_form_data", "process_data", "contact_info"),
                    "预约型落地页需要流程、留资和联系方式");
            default -> module("booking-management", "预约排班", templateId,
                    List.of("calendar_data", "advanced_filters", "form_modal"),
                    "预约管理需要日历、筛选和表单");
        };
    }

    private FeatureModuleManifest contentModule(String templateId) {
        return switch (templateId) {
            case "vue-web-mobile" -> module("mobile-content", "移动内容流", templateId,
                    List.of("content_feed", "category_page", "tabbar_config"),
                    "移动内容应用需要信息流、分类和导航");
            case "vue-web-landing" -> module("landing-content", "内容营销", templateId,
                    List.of("case_studies", "faq_content", "social_proof_data"),
                    "内容营销页需要案例、问答和信任背书");
            default -> module("content-management", "内容管理", templateId,
                    List.of("content_data", "search_bar", "pro_table", "form_modal"),
                    "内容管理需要内容数据、搜索、列表和编辑");
        };
    }

    private FeatureModuleManifest analyticsModule(String templateId) {
        return switch (templateId) {
            case "vue-web-mobile" -> module("mobile-analytics", "移动数据概览", templateId,
                    List.of("mobile_campaigns", "member_benefits"),
                    "移动端运营需要活动和会员数据");
            case "vue-web-landing" -> module("landing-proof", "增长证明", templateId,
                    List.of("social_proof_data", "comparison_data", "pricing_packages"),
                    "展示型分析诉求通常以数据证明和方案对比呈现");
            default -> module("analytics-reporting", "数据报表", templateId,
                    List.of("statistics_cards", "operations_data", "report_data", "activity_timeline"),
                    "报表分析需要指标、趋势、运营和活动数据");
        };
    }

    private FeatureModuleManifest settingsModule(String templateId) {
        return switch (templateId) {
            case "vue-web-mobile" -> module("mobile-profile-settings", "移动个人设置", templateId,
                    List.of("profile_page", "member_benefits"),
                    "移动设置集中在个人中心和权益配置");
            case "vue-web-landing" -> module("landing-contact-settings", "联系配置", templateId,
                    List.of("contact_info", "lead_form_data"),
                    "展示型设置主要影响联系方式和留资字段");
            default -> module("admin-settings", "系统设置", templateId,
                    List.of("settings_page", "permission_matrix", "app_store"),
                    "管理端设置需要配置页、权限矩阵和应用状态");
        };
    }

    private FeatureModuleManifest orderModule(String templateId) {
        return switch (templateId) {
            case "vue-web-mobile" -> module("mobile-orders", "移动端订单", templateId,
                    List.of("orders_view", "order_list_data", "cart_store"),
                    "移动端订单需要订单列表、购物车和数据");
            case "vue-web-landing" -> module("landing-pricing", "价格方案", templateId,
                    List.of("pricing_packages", "comparison_data"),
                    "订单型落地页需要价格和对比");
            case "vue-web-basic" -> module("basic-orders", "订单展示", templateId,
                    List.of("pro_table", "detail_data", "mock_data"),
                    "通用订单需要列表和详情");
            default -> module("admin-orders", "订单管理", templateId,
                    List.of("orders_page", "table_columns", "bulk_actions", "report_data"),
                    "后台订单管理需要订单页、表格和批量操作");
        };
    }

    private FeatureModuleManifest userManageModule(String templateId) {
        return switch (templateId) {
            case "vue-web-mobile" -> module("mobile-member", "会员管理", templateId,
                    List.of("member_profile_data", "profile_view", "user_store"),
                    "移动会员需要会员资料和个人中心");
            default -> module("admin-users", "用户管理", templateId,
                    List.of("users_page", "users", "permission_matrix", "bulk_actions"),
                    "后台用户管理需要用户页、权限和批量操作");
        };
    }

    private FeatureModuleManifest searchModule(String templateId) {
        return switch (templateId) {
            case "vue-web-mobile" -> module("mobile-search", "移动搜索", templateId,
                    List.of("search_page", "mobile_filters"),
                    "移动搜索需要搜索页和筛选器");
            case "vue-web-landing" -> module("landing-search", "内容搜索", templateId,
                    List.of("faq_data", "features_data"),
                    "落地页搜索通常体现在问答和特性过滤");
            default -> module("admin-search", "搜索筛选", templateId,
                    List.of("search_bar", "advanced_filters", "pro_table"),
                    "后台搜索需要搜索栏、筛选器和表格");
        };
    }

    private FeatureModuleManifest galleryModule(String templateId) {
        return switch (templateId) {
            case "vue-web-mobile" -> module("mobile-gallery", "移动图库", templateId,
                    List.of("category_page", "product_list"),
                    "移动图库需要分类和列表");
            default -> module("basic-gallery", "图库展示", templateId,
                    List.of("gallery_page", "categories_data", "content_data"),
                    "图库需要图库页、分类和内容");
        };
    }

    private FeatureModuleManifest contactModule(String templateId) {
        return switch (templateId) {
            case "vue-web-mobile" -> module("mobile-contact", "移动联系", templateId,
                    List.of("profile_page", "member_profile_data"),
                    "移动联系通常在个人中心");
            case "vue-web-landing" -> module("landing-contact", "落地页联系", templateId,
                    List.of("contact_data", "lead_form_data"),
                    "落地页联系需要联系数据和留资表单");
            default -> module("basic-contact", "联系我们", templateId,
                    List.of("contact_page", "contact_info"),
                    "通用联系页需要表单和联系方式");
        };
    }

    private FeatureModuleManifest pricingModule(String templateId) {
        return switch (templateId) {
            case "vue-web-landing" -> module("landing-pricing", "落地页定价", templateId,
                    List.of("pricing_packages", "comparison_data"),
                    "落地页定价需要方案和对比");
            default -> module("admin-pricing", "定价方案", templateId,
                    List.of("report_data", "mock_data"),
                    "后台定价需要报表和数据");
        };
    }

    private FeatureModuleManifest testimonialModule(String templateId) {
        return switch (templateId) {
            case "vue-web-landing" -> module("landing-testimonials", "客户见证", templateId,
                    List.of("testimonials_data", "social_proof_data"),
                    "落地页评价需要客户评价和社会证明");
            default -> module("admin-testimonials", "客户评价", templateId,
                    List.of("content_data", "mock_data"),
                    "后台评价需要内容和数据");
        };
    }

    private FeatureModuleManifest featureModule(String templateId) {
        return switch (templateId) {
            case "vue-web-landing" -> module("landing-features", "落地页特性", templateId,
                    List.of("features_data", "highlights_data"),
                    "落地页特性需要功能数据和亮点");
            case "vue-web-basic" -> module("basic-features", "特性展示", templateId,
                    List.of("about_data", "categories_data"),
                    "通用特性需要关于数据和分类");
            default -> module("admin-features", "功能配置", templateId,
                    List.of("settings_page", "app_store"),
                    "后台功能需要配置和状态");
        };
    }

    private FeatureModuleManifest categoryModule(String templateId) {
        return switch (templateId) {
            case "vue-web-mobile" -> module("mobile-category", "移动端分类", templateId,
                    List.of("category_page", "filter_config", "mobile_filters"),
                    "移动端分类需要分类页和筛选");
            default -> module("basic-category", "分类管理", templateId,
                    List.of("categories_data", "pro_table", "form_modal"),
                    "分类管理需要分类数据、列表和编辑");
        };
    }

    private FeatureModuleManifest exportModule(String templateId) {
        return switch (templateId) {
            case "vue-web-mobile" -> module("mobile-export", "移动端导出", templateId,
                    List.of("order_list_data", "member_profile_data"),
                    "移动端导出需要订单和会员数据");
            default -> module("admin-export", "导入导出", templateId,
                    List.of("table_columns", "bulk_actions", "report_data"),
                    "导入导出需要表格、批量操作和报表");
        };
    }

    private FeatureModuleManifest module(
            String moduleId,
            String name,
            String templateId,
            List<String> slotIds,
            String reason
    ) {
        return new FeatureModuleManifest(moduleId, name, templateId, slotIds, reason);
    }

    private List<String> prepend(String requiredSlot, List<String> capabilitySlots) {
        return prepend(List.of(requiredSlot), capabilitySlots);
    }

    /** 保留文件 slot 在前、能力 slot 在后的稳定顺序，便于事件与测试定位。 */
    private List<String> prepend(List<String> requiredSlots, List<String> capabilitySlots) {
        List<String> slots = new ArrayList<>(requiredSlots);
        slots.addAll(capabilitySlots);
        return List.copyOf(slots);
    }

    /** Vue 模板规划的内部不可变结果。 */
    record VueTemplateFeaturePlan(
            String baseTemplateId,
            List<FeatureModuleManifest> modules
    ) {
        public VueTemplateFeaturePlan {
            if (baseTemplateId == null || baseTemplateId.isBlank()) {
                throw new IllegalArgumentException("Vue 基础模板不能为空");
            }
            modules = modules == null ? List.of() : List.copyOf(modules);
        }
    }
}
