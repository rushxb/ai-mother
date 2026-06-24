package com.rush.rushaicodemother.orchestration.create;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic CREATE recipe renderer.
 * <p>
 * AI provides a compact variable spec; this service turns it into stable code patches.
 */
@Service
public class CreateRecipeRendererService {

    private static final String LANDING_TEMPLATE = "vue-web-landing";
    private static final String ADMIN_TEMPLATE = "vue-web-admin";
    private static final String BASIC_TEMPLATE = "vue-web-basic";
    private static final String MOBILE_TEMPLATE = "vue-web-mobile";
    private static final String BACKEND_TEMPLATE = "go-sqlite-backend-basic";
    private static final Pattern IDENTIFIER_CLEANUP = Pattern.compile("[^A-Za-z0-9_]");

    private final LandingSlotFallbackRenderer landingRenderer;
    private final TemplateVariableEngine variableEngine;

    public CreateRecipeRendererService(LandingSlotFallbackRenderer landingRenderer) {
        this(landingRenderer, new TemplateVariableEngine());
    }

    @Autowired
    public CreateRecipeRendererService(LandingSlotFallbackRenderer landingRenderer, TemplateVariableEngine variableEngine) {
        this.landingRenderer = landingRenderer;
        this.variableEngine = variableEngine;
    }

    public boolean supportsTemplate(String templateId) {
        return Set.of(LANDING_TEMPLATE, ADMIN_TEMPLATE, BASIC_TEMPLATE, MOBILE_TEMPLATE, BACKEND_TEMPLATE).contains(templateId);
    }

    public RecipeRenderResult render(String userMessage, SlotGroup group, CreateSpec spec) {
        if (group == null || spec == null) {
            return RecipeRenderResult.empty();
        }
        TemplateVariableManifest manifest = variableEngine.manifest(group.templateId(), spec);
        if (landingRenderer.supports(group)) {
            LandingSlotFallbackRenderer.LandingFallback result =
                    landingRenderer.renderFromSpec(userMessage, group, spec, "create_spec_recipe");
            return new RecipeRenderResult(result.filledSlots(), result.patchOperations(), result.totalChars(), result.summary(), manifest);
        }
        return switch (group.templateId()) {
            case ADMIN_TEMPLATE -> renderAdmin(userMessage, group, spec, manifest);
            case BASIC_TEMPLATE -> renderBasic(userMessage, group, spec, manifest);
            case MOBILE_TEMPLATE -> renderMobile(userMessage, group, spec, manifest);
            case BACKEND_TEMPLATE -> renderBackend(userMessage, group, spec, manifest);
            default -> RecipeRenderResult.empty();
        };
    }

    private RecipeRenderResult renderAdmin(String userMessage, SlotGroup group, CreateSpec spec, TemplateVariableManifest manifest) {
        AdminRecipe recipe = adminRecipe(userMessage, spec);
        List<PatchOperation> operations = new ArrayList<>();
        for (String slotId : group.slotIds()) {
            switch (slotId) {
                case "dashboard_content" -> operations.add(PatchOperation.modify("src/views/DashboardView.vue", adminDashboardView(recipe)));
                case "mock_data" -> operations.add(PatchOperation.modify("src/data/adminData.ts", adminData(recipe)));
                case "table_columns" -> operations.add(PatchOperation.modify("src/data/table.columns.ts", tableColumns(recipe)));
                case "sidebar_menu" -> operations.add(PatchOperation.modify("src/data/sidebar.menu.ts", sidebarMenu(recipe)));
                case "statistics_cards" -> operations.add(PatchOperation.modify("src/data/statistics.ts", statistics(recipe)));
                case "operations_data" -> operations.add(PatchOperation.modify("src/data/operations.ts", operationsData(recipe)));
                case "activity_timeline" -> operations.add(PatchOperation.modify("src/data/activity.ts", activityData(recipe)));
                case "theme_tokens" -> operations.add(PatchOperation.modify("src/styles/theme.css", themeCss(recipe)));
                default -> {
                }
            }
        }
        if (operations.isEmpty()) {
            return RecipeRenderResult.empty();
        }
        List<String> filledSlots = filledSlotsForOperations(group.slotIds(), operations, ADMIN_TEMPLATE);
        int totalChars = operations.stream().mapToInt(operation -> StrUtil.blankToDefault(operation.content(), operation.newContent()).length()).sum();
        return new RecipeRenderResult(filledSlots, operations, totalChars, "AI spec + 本地 admin recipe 已生成后台数据与页面", manifest);
    }

    private RecipeRenderResult renderBasic(String userMessage, SlotGroup group, CreateSpec spec, TemplateVariableManifest manifest) {
        BasicRecipe recipe = basicRecipe(userMessage, spec);
        List<PatchOperation> operations = new ArrayList<>();
        for (String slotId : group.slotIds()) {
            switch (slotId) {
                case "home_content" -> operations.add(PatchOperation.modify("src/views/HomeView.vue", basicHomeView()));
                case "mock_data" -> operations.add(PatchOperation.modify("src/data/siteData.ts", basicSiteData(recipe)));
                case "app_config" -> operations.add(PatchOperation.modify("src/data/app.config.ts", basicAppConfig(recipe)));
                case "navigation_items" -> operations.add(PatchOperation.modify("src/data/navigation.ts", basicNavigation()));
                case "theme_tokens" -> operations.add(PatchOperation.modify("src/styles/theme.css", basicThemeCss(recipe)));
                default -> {
                }
            }
        }
        return toResult(group, operations, "AI spec + 本地 basic recipe 已生成通用应用骨架", manifest);
    }

    private RecipeRenderResult renderMobile(String userMessage, SlotGroup group, CreateSpec spec, TemplateVariableManifest manifest) {
        BasicRecipe recipe = basicRecipe(userMessage, spec);
        List<PatchOperation> operations = new ArrayList<>();
        for (String slotId : group.slotIds()) {
            switch (slotId) {
                case "home_content" -> operations.add(PatchOperation.modify("src/views/HomeView.vue", mobileHomeView()));
                case "mock_data" -> operations.add(PatchOperation.modify("src/data/mock.ts", mobileMockData(recipe)));
                case "tabbar_config" -> operations.add(PatchOperation.modify("src/data/tabbar.ts", mobileTabbar()));
                case "product_list" -> operations.add(PatchOperation.modify("src/data/products.ts", mobileProducts(recipe)));
                case "theme_tokens" -> operations.add(PatchOperation.modify("src/styles/mobile.css", mobileThemeCss(recipe)));
                default -> {
                }
            }
        }
        return toResult(group, operations, "AI spec + 本地 mobile recipe 已生成移动端应用骨架", manifest);
    }

    private RecipeRenderResult renderBackend(String userMessage, SlotGroup group, CreateSpec spec, TemplateVariableManifest manifest) {
        BackendRecipe recipe = backendRecipe(userMessage, spec);
        List<PatchOperation> operations = new ArrayList<>();
        for (String slotId : group.slotIds()) {
            switch (slotId) {
                case "domain_contract" -> operations.add(PatchOperation.modify("internal/domain/model.go", domainContract(recipe)));
                case "module_model" -> operations.add(PatchOperation.add("internal/modules/" + recipe.packageName() + "/model.go", backendModel(recipe)));
                case "module_repository" -> operations.add(PatchOperation.add("internal/modules/" + recipe.packageName() + "/repository.go", backendRepository(recipe)));
                case "module_service" -> operations.add(PatchOperation.add("internal/modules/" + recipe.packageName() + "/service.go", backendService(recipe)));
                case "module_handler" -> operations.add(PatchOperation.add("internal/modules/" + recipe.packageName() + "/handler.go", backendHandler(recipe)));
                case "database_schema" -> operations.add(PatchOperation.appendSqlMigration("sql/schema.sql", backendSchema(recipe)));
                case "module_import" -> operations.add(PatchOperation.goAddImport("cmd/server/main.go", "backend-template/internal/modules/" + recipe.packageName()));
                case "server_wiring" -> operations.add(PatchOperation.insertBeforeMarker("cmd/server/main.go", "// @AI_INJECT_MODULE_WIRING: register", backendWiring(recipe)));
                default -> {
                }
            }
        }
        if (operations.isEmpty()) {
            return RecipeRenderResult.empty();
        }
        List<String> filledSlots = filledSlotsForOperations(group.slotIds(), operations, BACKEND_TEMPLATE);
        int totalChars = operations.stream()
                .mapToInt(operation -> StrUtil.blankToDefault(operation.content(), operation.newContent()).length())
                .sum();
        return new RecipeRenderResult(filledSlots, operations, totalChars, "AI spec + 本地 backend recipe 已生成 CRUD 分层模块", manifest);
    }

    private RecipeRenderResult toResult(SlotGroup group, List<PatchOperation> operations, String summary, TemplateVariableManifest manifest) {
        if (operations.isEmpty()) {
            return RecipeRenderResult.empty();
        }
        Set<String> paths = operations.stream().map(PatchOperation::relativePath).collect(java.util.stream.Collectors.toSet());
        List<String> filledSlots = group.slotIds().stream()
                .filter(slotId -> paths.contains(basicOrMobilePath(slotId, group.templateId())))
                .toList();
        int totalChars = operations.stream().mapToInt(operation -> StrUtil.blankToDefault(operation.content(), operation.newContent()).length()).sum();
        return new RecipeRenderResult(filledSlots, operations, totalChars, summary, manifest);
    }

    private String basicOrMobilePath(String slotId, String templateId) {
        if (BASIC_TEMPLATE.equals(templateId)) {
            return switch (slotId) {
                case "home_content" -> "src/views/HomeView.vue";
                case "mock_data" -> "src/data/siteData.ts";
                case "app_config" -> "src/data/app.config.ts";
                case "navigation_items" -> "src/data/navigation.ts";
                case "theme_tokens" -> "src/styles/theme.css";
                default -> "";
            };
        }
        if (MOBILE_TEMPLATE.equals(templateId)) {
            return switch (slotId) {
                case "home_content" -> "src/views/HomeView.vue";
                case "mock_data" -> "src/data/mock.ts";
                case "tabbar_config" -> "src/data/tabbar.ts";
                case "product_list" -> "src/data/products.ts";
                case "theme_tokens" -> "src/styles/mobile.css";
                default -> "";
            };
        }
        return "";
    }

    private List<String> filledSlotsForOperations(List<String> slotIds, List<PatchOperation> operations, String templateId) {
        Set<String> filled = new LinkedHashSet<>();
        if (ADMIN_TEMPLATE.equals(templateId)) {
            operations.forEach(operation -> {
                String path = operation.relativePath();
                if ("src/views/DashboardView.vue".equals(path)) filled.add("dashboard_content");
                if ("src/data/adminData.ts".equals(path)) filled.add("mock_data");
                if ("src/data/table.columns.ts".equals(path)) filled.add("table_columns");
                if ("src/data/sidebar.menu.ts".equals(path)) filled.add("sidebar_menu");
                if ("src/data/statistics.ts".equals(path)) filled.add("statistics_cards");
                if ("src/data/operations.ts".equals(path)) filled.add("operations_data");
                if ("src/data/activity.ts".equals(path)) filled.add("activity_timeline");
                if ("src/styles/theme.css".equals(path)) filled.add("theme_tokens");
            });
        } else if (BACKEND_TEMPLATE.equals(templateId)) {
            operations.forEach(operation -> {
                String path = operation.relativePath();
                if ("internal/domain/model.go".equals(path)) filled.add("domain_contract");
                if (path.endsWith("/model.go")) filled.add("module_model");
                if (path.endsWith("/repository.go")) filled.add("module_repository");
                if (path.endsWith("/service.go")) filled.add("module_service");
                if (path.endsWith("/handler.go")) filled.add("module_handler");
                if ("sql/schema.sql".equals(path)) filled.add("database_schema");
                if (PatchOperation.ACTION_GO_ADD_IMPORT.equals(operation.action())) filled.add("module_import");
                if (PatchOperation.ACTION_INSERT_BEFORE_MARKER.equals(operation.action())) filled.add("server_wiring");
            });
        }
        return slotIds.stream().filter(filled::contains).toList();
    }

    private AdminRecipe adminRecipe(String userMessage, CreateSpec spec) {
        String brand = firstNonBlank(spec.product() == null ? null : spec.product().brandName(), inferBrand(userMessage, "运营中台"));
        String domain = firstNonBlank(readableDomain(spec.product() == null ? null : spec.product().domain()), inferIndustry(userMessage));
        String primary = validHex(spec.frontend() == null || spec.frontend().theme() == null ? null : spec.frontend().theme().primary(), "#2563eb");
        String accent = validHex(spec.frontend() == null || spec.frontend().theme() == null ? null : spec.frontend().theme().accent(), "#f97316");
        List<CreateSpec.EntitySpec> entities = spec.entities() == null || spec.entities().isEmpty()
                ? List.of(new CreateSpec.EntitySpec("Record", "业务记录", List.of(
                new CreateSpec.FieldSpec("name", "string", "名称", true, List.of()),
                new CreateSpec.FieldSpec("owner", "string", "负责人", false, List.of()),
                new CreateSpec.FieldSpec("status", "enum", "状态", false, List.of("启用", "停用")),
                new CreateSpec.FieldSpec("amount", "decimal", "金额", false, List.of())
        ), List.of(), List.of()))
                : spec.entities().stream().limit(3).toList();
        CreateSpec.EntitySpec primaryEntity = entities.getFirst();
        String entityLabel = firstNonBlank(primaryEntity.label(), "业务记录");
        List<RecipeField> recipeFields = normalizeFields(primaryEntity.fields());
        FrontendOptions frontend = frontendOptions(spec);
        return new AdminRecipe(brand, domain, primary, accent, entityLabel, recipeFields, frontend,
                firstNonBlank(spec.content() == null ? null : spec.content().mockDataStyle(), domain + "运营数据"));
    }

    private BackendRecipe backendRecipe(String userMessage, CreateSpec spec) {
        CreateSpec.EntitySpec entity = spec.entities() == null || spec.entities().isEmpty()
                ? new CreateSpec.EntitySpec(inferEntityName(userMessage), inferEntityLabel(userMessage), List.of(
                new CreateSpec.FieldSpec("name", "string", "名称", true, List.of()),
                new CreateSpec.FieldSpec("status", "enum", "状态", false, List.of("启用", "停用")),
                new CreateSpec.FieldSpec("owner", "string", "负责人", false, List.of()),
                new CreateSpec.FieldSpec("remark", "string", "备注", false, List.of())
        ), List.of(), List.of())
                : spec.entities().getFirst();
        String structName = pascal(firstNonBlank(entity.name(), inferEntityName(userMessage)));
        String label = firstNonBlank(entity.label(), inferEntityLabel(userMessage));
        String packageName = lowerIdentifier(structName);
        List<RecipeField> fields = normalizeFields(entity.fields());
        BackendOptions options = backendOptions(spec);
        return new BackendRecipe(packageName, structName, label, tableName(packageName), fields, options,
                databaseIndexes(spec, fields, tableName(packageName)));
    }

    private BasicRecipe basicRecipe(String userMessage, CreateSpec spec) {
        String brand = firstNonBlank(spec.product() == null ? null : spec.product().brandName(), inferBrand(userMessage, "Nexa Studio"));
        CreateSpec.Landing landing = spec.content() == null ? null : spec.content().landing();
        String headline = firstNonBlank(landing == null ? null : landing.headline(), brand + " 的数字化体验");
        String description = firstNonBlank(landing == null ? null : landing.description(),
                "围绕核心用户、服务流程和数据反馈，快速搭建可预览、可继续编辑的应用骨架。");
        String domain = firstNonBlank(readableDomain(spec.product() == null ? null : spec.product().domain()), inferIndustry(userMessage));
        String primary = validHex(spec.frontend() == null || spec.frontend().theme() == null ? null : spec.frontend().theme().primary(), "#2563eb");
        String accent = validHex(spec.frontend() == null || spec.frontend().theme() == null ? null : spec.frontend().theme().accent(), "#f97316");
        String entityLabel = spec.entities() == null || spec.entities().isEmpty()
                ? "业务"
                : firstNonBlank(spec.entities().getFirst().label(), "业务");
        return new BasicRecipe(brand, headline, description, domain, primary, accent, entityLabel, frontendOptions(spec));
    }

    private String basicHomeView() {
        return """
                <template>
                  <section id="hero" class="hero">
                    <div class="hero-copy">
                      <span class="eyebrow">{{ hero.eyebrow }}</span>
                      <h1>{{ hero.title }}</h1>
                      <p>{{ hero.description }}</p>
                      <div class="hero-actions">
                        <a v-for="action in hero.actions" :key="action.label" :class="['btn', action.type]" :href="action.path">
                          {{ action.label }}
                        </a>
                      </div>
                    </div>
                    <div class="hero-panel">
                      <div v-for="item in features" :key="item.title" class="metric-card">
                        <strong>{{ item.stat }}</strong>
                        <span>{{ item.title }}</span>
                        <p>{{ item.text }}</p>
                      </div>
                    </div>
                  </section>

                  <section id="modules" class="content-band">
                    <SectionTitle eyebrow="Modules" title="业务模块" description="围绕真实领域模型组织页面、数据和行动路径。" />
                    <div class="card-grid">
                      <article v-for="card in cards" :key="card.id" class="feature-card">
                        <span>{{ card.id }}</span>
                        <h3>{{ card.title }}</h3>
                        <p>{{ card.desc }}</p>
                      </article>
                    </div>
                  </section>

                  <section id="timeline" class="split-section">
                    <SectionTitle title="交付流程" description="从需求定义到上线验证，每一步都可以继续在 EDIT 模式精修。" />
                    <ol class="timeline">
                      <li v-for="(item, index) in timeline" :key="item">
                        <span>{{ String(index + 1).padStart(2, '0') }}</span>
                        {{ item }}
                      </li>
                    </ol>
                  </section>
                </template>

                <script setup lang="ts">
                import SectionTitle from '@/components/SectionTitle.vue'
                import { cards, features, hero, timeline } from '@/data/siteData'
                </script>
                """;
    }

    private String basicSiteData(BasicRecipe recipe) {
        return """
                export interface SiteInfo {
                  brand: string
                  slogan: string
                  nav: NavItem[]
                }

                export interface NavItem {
                  label: string
                  path: string
                  icon?: string
                }

                export interface HeroData {
                  eyebrow: string
                  title: string
                  description: string
                  actions: HeroAction[]
                }

                export interface HeroAction {
                  label: string
                  path: string
                  type: 'primary' | 'secondary'
                }

                export interface FeatureItem {
                  title: string
                  text: string
                  stat: string
                }

                export interface CardItem {
                  id: string
                  title: string
                  desc: string
                }

                export const site: SiteInfo = {
                  brand: '%s',
                  slogan: '%s',
                  nav: [
                    { label: '首页', path: '#hero' },
                    { label: '模块', path: '#modules' },
                    { label: '流程', path: '#timeline' },
                    { label: '联系', path: '#contact' }
                  ]
                }

                export const hero: HeroData = {
                  eyebrow: '%s',
                  title: '%s',
                  description: '%s',
                  actions: [
                    { label: '查看模块', path: '#modules', type: 'primary' },
                    { label: '联系咨询', path: '#contact', type: 'secondary' }
                  ]
                }

                export const features: FeatureItem[] = [
                  { title: '领域建模', text: '围绕%s沉淀实体、字段和页面结构。', stat: 'Spec' },
                  { title: '稳定生成', text: '代码由本地 recipe 渲染，首轮生成更稳定。', stat: 'Recipe' },
                  { title: '持续增强', text: '后续可继续用 EDIT 精修页面和交互。', stat: 'Edit' }
                ]

                export const cards: CardItem[] = [
                  { id: 'core', title: '%s核心流程', desc: '把关键业务动作整理成清晰的页面路径。' },
                  { id: 'data', title: '数据样例', desc: '预置贴合行业的 mock 数据，便于立即预览。' },
                  { id: 'growth', title: '增长入口', desc: '保留咨询、转化或下一步行动的扩展空间。' }
                ]

                export const timeline: string[] = [
                  '理解需求并生成创意规格',
                  '本地 recipe 渲染稳定代码',
                  '执行校验和构建',
                  '进入预览并继续增强'
                ]
                """.formatted(
                escape(recipe.brand()),
                escape(recipe.headline()),
                escape(recipe.domain()),
                escape(recipe.headline()),
                escape(recipe.description()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel())
        );
    }

    private String basicAppConfig(BasicRecipe recipe) {
        return """
                export const appConfig = {
                  title: '%s',
                  description: '%s',
                  copyright: '© 2026 %s'
                }
                """.formatted(escape(recipe.brand()), escape(recipe.description()), escape(recipe.brand()));
    }

    private String basicNavigation() {
        return """
                export const navItems = [
                  { label: '首页', path: '#hero' },
                  { label: '模块', path: '#modules' },
                  { label: '流程', path: '#timeline' },
                  { label: '联系', path: '#contact' }
                ]
                """;
    }

    private String basicThemeCss(BasicRecipe recipe) {
        return """
                :root {
                  --color-primary: %s;
                  --color-secondary: %s;
                  --color-bg: #ffffff;
                  --color-text: #111827;
                  --spacing-md: 1rem;
                  --radius-md: 0.5rem;
                }
                """.formatted(recipe.primary(), recipe.accent());
    }

    private String mobileHomeView() {
        return """
                <template>
                  <MobileHomePage />
                </template>

                <script setup lang="ts">
                import MobileHomePage from '@/pages/MobileHomePage.vue'
                </script>
                """;
    }

    private String mobileMockData(BasicRecipe recipe) {
        return """
                export interface Banner {
                  id: number
                  image: string
                  title: string
                  link?: string
                }

                export interface Product {
                  id: number
                  name: string
                  price: number
                  originalPrice?: number
                  image: string
                  description?: string
                  tags?: string[]
                }

                export interface Category {
                  id: number
                  name: string
                  icon?: string
                }

                export interface TabItem {
                  name: string
                  title: string
                  icon: string
                  path: string
                }

                export const banners: Banner[] = [
                  { id: 1, image: '/banner1.jpg', title: '%s' },
                  { id: 2, image: '/banner2.jpg', title: '%s精选推荐' },
                  { id: 3, image: '/banner3.jpg', title: '限时体验活动' }
                ]

                export const categories: Category[] = [
                  { id: 1, name: '%s', icon: 'apps-o' },
                  { id: 2, name: '推荐', icon: 'gift-o' },
                  { id: 3, name: '热门', icon: 'hot-o' },
                  { id: 4, name: '新品', icon: 'new-o' },
                  { id: 5, name: '会员', icon: 'star-o' },
                  { id: 6, name: '全部', icon: 'ellipsis' }
                ]

                export const products: Product[] = [
                  { id: 1, name: '%s基础服务', price: 99, originalPrice: 199, image: '/product1.jpg', tags: ['推荐', '热销'] },
                  { id: 2, name: '%s增长方案', price: 199, image: '/product2.jpg', tags: ['精选'] },
                  { id: 3, name: '%s定制包', price: 299, originalPrice: 399, image: '/product3.jpg', tags: ['专业'] },
                  { id: 4, name: '%s体验课', price: 49, image: '/product4.jpg', tags: ['限时'] }
                ]

                export const tabbar: TabItem[] = [
                  { name: 'home', title: '首页', icon: 'home-o', path: '/' },
                  { name: 'category', title: '分类', icon: 'apps-o', path: '/category' },
                  { name: 'orders', title: '订单', icon: 'orders-o', path: '/orders' },
                  { name: 'profile', title: '我的', icon: 'user-o', path: '/profile' }
                ]
                """.formatted(
                escape(recipe.headline()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel())
        );
    }

    private String mobileTabbar() {
        return """
                export const tabbar = [
                  { name: 'home', title: '首页', icon: 'home-o' },
                  { name: 'category', title: '分类', icon: 'apps-o' },
                  { name: 'orders', title: '订单', icon: 'orders-o' },
                  { name: 'profile', title: '我的', icon: 'user-o' }
                ]
                """;
    }

    private String mobileProducts(BasicRecipe recipe) {
        return """
                export const products = [
                  { id: 1, name: '%s基础服务', price: 99, image: '/product1.jpg', tags: ['推荐'] },
                  { id: 2, name: '%s进阶方案', price: 199, image: '/product2.jpg', tags: ['热门'] },
                  { id: 3, name: '%s定制服务', price: 299, image: '/product3.jpg', tags: ['专业'] }
                ]
                """.formatted(escape(recipe.entityLabel()), escape(recipe.entityLabel()), escape(recipe.entityLabel()));
    }

    private String mobileThemeCss(BasicRecipe recipe) {
        return """
                :root {
                  --color-primary: %s;
                  --color-bg: #f7f8fa;
                  --color-accent: %s;
                  --tabbar-height: 50px;
                  --safe-area-bottom: env(safe-area-inset-bottom);
                }
                """.formatted(recipe.primary(), recipe.accent());
    }

    private String adminDashboardView(AdminRecipe recipe) {
        return """
                <template>
                  <div :class="['dashboard-view', dashboardClass]">
                    <div class="dashboard-toolbar">
                      <div>
                        <p class="text-sm text-muted-foreground">%s</p>
                        <h1 class="text-2xl font-semibold">%s工作台</h1>
                      </div>
                      <div class="toolbar-actions">
                        <Button v-if="enabledInteractions.includes('batch')" variant="outline">批量处理</Button>
                        <Button v-if="enabledInteractions.includes('export')" variant="outline">导出</Button>
                        <Button>新增%s</Button>
                      </div>
                    </div>

                    <div v-if="enabledInteractions.includes('filter')" class="filter-strip">
                      <span v-for="item in filterChips" :key="item" class="filter-chip">{{ item }}</span>
                    </div>

                    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 metric-grid">
                      <Card v-for="item in metrics" :key="item.label">
                        <CardHeader class="flex flex-row items-center justify-between space-y-0 pb-2">
                          <CardTitle class="text-sm font-medium">{{ item.label }}</CardTitle>
                        </CardHeader>
                        <CardContent>
                          <div class="text-2xl font-bold">{{ item.value }}</div>
                          <p class="text-xs" :class="item.trendType === 'up' ? 'text-green-500' : 'text-red-500'">
                            {{ item.trend }}
                          </p>
                        </CardContent>
                      </Card>
                    </div>

                    <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
                      <Card>
                        <CardHeader class="flex flex-row items-center justify-between">
                          <CardTitle>{{ primaryViz.title }}</CardTitle>
                          <span class="text-sm text-muted-foreground">{{ primaryViz.range }}</span>
                        </CardHeader>
                        <CardContent>
                          <div class="flex items-end gap-2 h-[200px] p-4">
                            <div
                              v-for="(height, index) in trendData"
                              :key="index"
                              class="flex-1 rounded-t-md bg-gradient-to-t from-primary/80 to-primary/40 transition-all duration-300"
                              :style="{ height: height + '%%' }"
                            />
                          </div>
                        </CardContent>
                      </Card>

                      <Card>
                        <CardHeader>
                          <CardTitle>{{ secondaryViz.title }}</CardTitle>
                        </CardHeader>
                        <CardContent>
                          <div class="space-y-3">
                            <div v-for="(item, index) in activities" :key="index" class="flex items-center gap-3 p-2 rounded-md hover:bg-muted/50 transition-colors">
                              <div class="w-2 h-2 rounded-full bg-primary" />
                              <span class="text-sm">{{ item }}</span>
                            </div>
                          </div>
                        </CardContent>
                      </Card>
                    </div>

                    <Card>
                      <CardHeader class="flex flex-row items-center justify-between">
                        <CardTitle>%s列表</CardTitle>
                        <Button variant="link" @click="router.push('/orders')">查看全部</Button>
                      </CardHeader>
                      <CardContent>
                        <div class="rounded-md border">
                          <table class="w-full caption-bottom text-sm">
                            <thead class="[&_tr]:border-b">
                              <tr class="border-b transition-colors hover:bg-muted/50">
                                <th v-for="column in columns" :key="column.key" class="h-12 px-4 text-left align-middle font-medium text-muted-foreground">
                                  {{ column.title }}
                                </th>
                              </tr>
                            </thead>
                            <tbody class="[&_tr:last-child]:border-0">
                              <tr v-for="row in orders" :key="row.no" class="border-b transition-colors hover:bg-muted/50">
                                <td class="p-4 align-middle font-medium">{{ row.no }}</td>
                                <td v-for="column in valueColumns" :key="column.key" class="p-4 align-middle">
                                  {{ row[column.key] ?? '-' }}
                                </td>
                                <td v-if="hasStatusColumn" class="p-4 align-middle">
                                  <Badge :variant="statusVariant(row.status)">{{ row.status }}</Badge>
                                </td>
                              </tr>
                            </tbody>
                          </table>
                        </div>
                      </CardContent>
                    </Card>
                  </div>
                </template>

                <script setup lang="ts">
                import { ref } from 'vue'
                import { useRouter } from 'vue-router'
                import { Button } from '@/components/ui/button'
                import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
                import { Badge } from '@/components/ui/badge'
                import { activities, dashboardClass, enabledInteractions, filterChips, metrics, orders, visualizationBlocks } from '@/data/adminData'
                import { columns } from '@/data/table.columns'

                const router = useRouter()
                const primaryViz = visualizationBlocks[0]
                const secondaryViz = visualizationBlocks[1] ?? visualizationBlocks[0]
                const trendData = ref(primaryViz.values)
                const valueColumns = columns.filter(item => item.key !== 'no' && item.key !== 'status')
                const hasStatusColumn = columns.some(item => item.key === 'status')

                function statusVariant(status: string): 'default' | 'secondary' | 'destructive' | 'outline' {
                  const map: Record<string, 'default' | 'secondary' | 'destructive' | 'outline'> = {
                    '已完成': 'default',
                    '待处理': 'secondary',
                    '已取消': 'destructive',
                    '进行中': 'outline'
                  }
                  return map[status] || 'outline'
                }
                </script>

                <style scoped>
                .dashboard-view {
                  padding: var(--dashboard-padding);
                  display: flex;
                  flex-direction: column;
                  gap: var(--dashboard-gap);
                }
                .dashboard-toolbar {
                  display: flex;
                  align-items: center;
                  justify-content: space-between;
                  gap: 16px;
                }
                .toolbar-actions {
                  display: flex;
                  gap: 8px;
                  flex-wrap: wrap;
                }
                .metric-grid {
                  gap: var(--dashboard-gap);
                }
                .filter-strip {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 8px;
                }
                .filter-chip {
                  border: 1px solid hsl(var(--border));
                  border-radius: var(--panel-radius);
                  padding: var(--chip-padding);
                  background: var(--surface-muted);
                  font-size: var(--table-font-size);
                }
                </style>
                """.formatted(
                escape(recipe.domain()),
                escape(recipe.brand()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel())
        );
    }

    private String adminData(AdminRecipe recipe) {
        String dashboardClass = "dashboard-" + recipe.frontend().density() + " " + String.join(" ", recipe.frontend().styleClasses());
        return """
                import type { DashboardMetrics, OrderInfo } from '@/types'

                export interface AdminSite {
                  brand: string
                  slogan: string
                  nav: AdminNavItem[]
                }

                export interface AdminNavItem {
                  label: string
                  path: string
                  icon?: string
                  children?: AdminNavItem[]
                }

                export const site: AdminSite = {
                  brand: '%s',
                  slogan: '%s数字化运营工作台',
                  nav: %s
                }

                export const dashboardClass = '%s'
                export const enabledInteractions = %s
                export const filterChips = %s
                export const visualizationBlocks = %s

                export const metrics: DashboardMetrics[] = [
                  { label: '活跃%s', value: '1,286', trend: '+12.6%%', trendType: 'up' },
                  { label: '待处理事项', value: '84', trend: '+8.2%%', trendType: 'up' },
                  { label: '本月转化', value: '32.4%%', trend: '+4.1%%', trendType: 'up' },
                  { label: '异常预警', value: '7', trend: '-2.0%%', trendType: 'down' }
                ]

                export const orders: OrderInfo[] = [
                %s
                ]

                export const activities: string[] = [
                  '%s数据同步完成，新增 42 条记录',
                  '运营负责人更新了%s状态',
                  '系统生成了%s周报',
                  '新的%s审核任务已分配'
                ]
                """.formatted(
                escape(recipe.brand()),
                escape(recipe.domain()),
                adminNavItems(recipe),
                escape(dashboardClass),
                tsStringArray(recipe.frontend().interactions()),
                tsStringArray(filterChips(recipe)),
                visualizationBlocks(recipe),
                escape(recipe.entityLabel()),
                adminRows(recipe),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel())
        );
    }

    private String tableColumns(AdminRecipe recipe) {
        List<RecipeField> fields = recipe.fields().stream().limit(4).toList();
        StringBuilder builder = new StringBuilder("export const columns = [\n");
        builder.append("  { key: 'no', title: '编号' },\n");
        for (RecipeField field : fields) {
            builder.append("  { key: '").append(camel(field.name())).append("', title: '").append(escape(field.label())).append("'");
            if (recipe.frontend().interactions().contains("sort")) {
                builder.append(", sortable: true");
            }
            builder.append(" },\n");
        }
        builder.append("  { key: 'status', title: '状态'");
        if (recipe.frontend().interactions().contains("filter")) {
            builder.append(", filterable: true");
        }
        builder.append(" }\n");
        builder.append("]\n");
        return builder.toString();
    }

    private String sidebarMenu(AdminRecipe recipe) {
        String items = recipe.frontend().navigation().isEmpty()
                ? """
                  { label: '总览', path: '#overview', icon: 'dashboard' },
                  { label: '%s管理', path: '#records', icon: 'table' },
                  { label: '数据分析', path: '#analytics', icon: 'chart' },
                  { label: '系统设置', path: '#settings', icon: 'settings' }
                """.formatted(escape(recipe.entityLabel()))
                : recipe.frontend().navigation().stream()
                .limit(6)
                .map(label -> "  { label: '" + escape(label) + "', path: '#" + lowerIdentifier(label) + "', icon: 'menu' }")
                .collect(java.util.stream.Collectors.joining(",\n"));
        return """
                export const sidebarMenu = [
                %s
                ]
                """.formatted(items);
    }

    private String statistics(AdminRecipe recipe) {
        String third = recipe.frontend().dataViz().contains("funnel") ? "漏斗转化" : "待处理";
        String fourth = recipe.frontend().dataViz().contains("ranking") ? "TOP 排名" : "完成率";
        return """
                export const statistics = [
                  { label: '%s总量', value: '1,286', trend: '+12.6%%', tone: 'green' },
                  { label: '本周新增', value: '148', trend: '+8.2%%', tone: 'blue' },
                  { label: '%s', value: '84', trend: '-3.1%%', tone: 'amber' },
                  { label: '%s', value: '92.4%%', trend: '+4.1%%', tone: 'violet' }
                ]
                """.formatted(escape(recipe.entityLabel()), escape(third), escape(fourth));
    }

    private String operationsData(AdminRecipe recipe) {
        String first = recipe.frontend().interactions().contains("batch") ? "批量待处理" : "待审核";
        String second = recipe.frontend().interactions().contains("export") ? "待导出数据" : "进行中任务";
        return """
                export const operationQueues = [
                  { label: '%s%s', value: 18, tone: 'warning' },
                  { label: '%s', value: 42, tone: 'default' },
                  { label: '异常预警', value: 7, tone: 'danger' }
                ]
                """.formatted(escape(first), escape(recipe.entityLabel()), escape(second));
    }

    private String activityData(AdminRecipe recipe) {
        String secondTitle = recipe.frontend().interactions().contains("drawer") ? "详情抽屉打开" : "状态变更";
        String thirdTitle = recipe.frontend().dataViz().contains("ranking") ? "排行刷新" : "周报生成";
        return """
                export const activities = [
                  { time: '09:00', title: '%s同步完成', description: '同步 128 条%s记录' },
                  { time: '10:30', title: '%s', description: '运营负责人更新了 12 条记录' },
                  { time: '14:00', title: '%s', description: '%s运营周报已生成' }
                ]
                """.formatted(escape(recipe.entityLabel()), escape(recipe.entityLabel()), escape(secondTitle), escape(thirdTitle), escape(recipe.domain()));
    }

    private String themeCss(AdminRecipe recipe) {
        DensityTokens density = densityTokens(recipe.frontend().density());
        String styleComment = String.join(", ", recipe.frontend().styleKeywords());
        return """
                /* styleKeywords: %s */
                :root {
                  --color-primary: %s;
                  --color-success: #10b981;
                  --color-warning: #f59e0b;
                  --color-danger: #ef4444;
                  --color-accent: %s;
                  --panel-bg: #ffffff;
                  --panel-radius: %s;
                  --dashboard-padding: %s;
                  --dashboard-gap: %s;
                  --table-row-height: %s;
                  --table-font-size: %s;
                  --chip-padding: %s;
                  --surface-muted: %s;
                }
                .style-ops {
                  --surface-muted: #f8fafc;
                }
                .style-premium {
                  --surface-muted: #f5f3ff;
                }
                .style-medical-trust {
                  --surface-muted: #ecfeff;
                }
                .style-education-warm {
                  --surface-muted: #fff7ed;
                }
                """.formatted(
                escape(styleComment),
                recipe.primary(),
                recipe.accent(),
                recipe.frontend().radius(),
                density.padding(),
                density.gap(),
                density.rowHeight(),
                density.fontSize(),
                density.chipPadding(),
                recipe.frontend().surfaceMuted()
        );
    }

    private String domainContract(BackendRecipe recipe) {
        return """
                package domain

                type PageRequest struct {
                	Current  int `json:"current"`
                	PageSize int `json:"pageSize"`
                }

                func (p *PageRequest) Normalize() {
                	if p.Current <= 0 {
                		p.Current = 1
                	}
                	if p.PageSize <= 0 || p.PageSize > 100 {
                		p.PageSize = 10
                	}
                }

                type Page[T any] struct {
                	Records  []T   `json:"records"`
                	Total    int64 `json:"total"`
                	Current  int   `json:"current"`
                	PageSize int   `json:"pageSize"`
                }
                """;
    }

    private String backendModel(BackendRecipe recipe) {
        StringBuilder fields = new StringBuilder();
        StringBuilder createFields = new StringBuilder();
        StringBuilder updateFields = new StringBuilder();
        StringBuilder voFields = new StringBuilder();
        for (RecipeField field : recipe.fields()) {
            String goName = pascal(field.name());
            String json = camel(field.name());
            String goType = goType(field.type());
            fields.append("\t").append(goName).append(" ").append(goType).append(" `json:\"").append(json).append("\"`\n");
            createFields.append("\t").append(goName).append(" ").append(goType).append(" `json:\"").append(json).append("\"");
            if (recipe.options().validationRequired() && field.required()) {
                createFields.append(" validate:\"required\"");
            }
            createFields.append("`\n");
            updateFields.append("\t").append(goName).append(" ").append(goType).append(" `json:\"").append(json).append("\"`\n");
            voFields.append("\t").append(goName).append(" ").append(goType).append(" `json:\"").append(json).append("\"`\n");
        }
        String imports = recipe.options().pagination()
                ? """
                import (
                	"time"

                	"backend-template/internal/domain"
                )
                """
                : """
                import "time"
                """;
        String pageRequest = recipe.options().pagination() ? "\tdomain.PageRequest\n" : "";
        String keyword = recipe.options().search() ? "\tKeyword string `json:\"keyword\"`\n" : "";
        String sortFields = recipe.options().sort()
                ? "\tSortBy string `json:\"sortBy\"`\n\tSortOrder string `json:\"sortOrder\"`\n"
                : "";
        String batchRequest = recipe.options().batchActions()
                ? """

                type BatchDeleteRequest struct {
                	IDs []int64 `json:"ids" validate:"required"`
                }
                """
                : "";
        return """
                package %s

                %s

                type %s struct {
                	ID int64 `json:"id"`
                %s	CreatedAt time.Time `json:"createdAt"`
                	UpdatedAt time.Time `json:"updatedAt"`
                }

                type Create%sRequest struct {
                %s}

                type Update%sRequest struct {
                	ID int64 `json:"id"%s`
                %s}

                type QueryRequest struct {
                %s%s%s
                	Status string `json:"status"`
                }
                %s

                type %sVO struct {
                	ID int64 `json:"id"`
                %s	CreatedAt time.Time `json:"createdAt"`
                	UpdatedAt time.Time `json:"updatedAt"`
                }
                """.formatted(
                recipe.packageName(),
                imports.stripTrailing(),
                recipe.structName(),
                fields,
                recipe.structName(),
                createFields,
                recipe.structName(),
                recipe.options().validationRequired() ? " validate:\"required\"" : "",
                updateFields,
                pageRequest,
                keyword,
                sortFields,
                batchRequest,
                recipe.structName(),
                voFields
        );
    }

    private String backendRepository(BackendRecipe recipe) {
        String columns = String.join(", ", recipe.fields().stream().map(field -> snake(field.name())).toList());
        String placeholders = String.join(", ", recipe.fields().stream().map(ignored -> "?").toList());
        String createArgs = String.join(", ", recipe.fields().stream().map(field -> "req." + pascal(field.name())).toList());
        String scanTargets = String.join(", ", recipe.fields().stream().map(field -> "&item." + pascal(field.name())).toList());
        String updateSet = String.join(", ", recipe.fields().stream().map(field -> snake(field.name()) + " = ?").toList());
        String updateArgs = String.join(", ", recipe.fields().stream().map(field -> "req." + pascal(field.name())).toList());
        String searchColumn = recipe.fields().isEmpty() ? "id" : snake(recipe.fields().getFirst().name());
        String updateWhere = recipe.options().softDelete() ? "where id = ? and is_deleted = 0" : "where id = ?";
        String deleteSql = recipe.options().softDelete()
                ? "update " + recipe.tableName() + " set is_deleted = 1, updated_at = current_timestamp where id = ?"
                : "delete from " + recipe.tableName() + " where id = ?";
        String detailWhere = recipe.options().softDelete() ? "where id = ? and is_deleted = 0" : "where id = ?";
        String paginationVars = recipe.options().pagination()
                ? """
                	limit := req.PageSize
                	offset := (req.Current - 1) * req.PageSize
                """
                : "";
        String paginationSql = recipe.options().pagination()
                ? """
                		limit ? offset ?
                """
                : "";
        String queryArgs = recipe.options().pagination() ? "append(args, limit, offset)..." : "args...";
        String softDeleteCondition = recipe.options().softDelete()
                ? "\tconditions := []string{\"is_deleted = 0\"}"
                : "\tconditions := make([]string, 0)";
        String searchCondition = recipe.options().search()
                ? """
                	if strings.TrimSpace(req.Keyword) != "" {
                		conditions = append(conditions, "%s like ?")
                		args = append(args, "%%"+strings.TrimSpace(req.Keyword)+"%%")
                	}
                """.formatted(searchColumn)
                : "";
        String orderBy = recipe.options().sort() ? "safeOrderBy(req)" : "\"order by id desc\"";
        String sortHelper = recipe.options().sort() ? safeOrderByFunction(recipe) : "";
        return """
                package %s

                import (
                	"database/sql"
                	"strings"
                )

                type Repository struct {
                	db *sql.DB
                }

                func NewRepository(db *sql.DB) *Repository {
                	return &Repository{db: db}
                }

                func (r *Repository) Create(req Create%sRequest) (int64, error) {
                	result, err := r.db.Exec(`
                		insert into %s (%s)
                		values (%s)
                	`, %s)
                	if err != nil {
                		return 0, err
                	}
                	return result.LastInsertId()
                }

                func (r *Repository) Update(req Update%sRequest) error {
                	_, err := r.db.Exec(`
                		update %s
                		set %s, updated_at = current_timestamp
                		%s
                	`, %s, req.ID)
                	return err
                }

                func (r *Repository) Delete(id int64) error {
                	_, err := r.db.Exec("%s", id)
                	return err
                }

                func (r *Repository) FindByID(id int64) (%s, error) {
                	return r.scanOne(`
                		select id, %s, created_at, updated_at
                		from %s
                		%s
                	`, id)
                }

                func (r *Repository) List(req QueryRequest) ([]%s, int64, error) {
                	where, args := buildWhere(req)
                	var total int64
                	if err := r.db.QueryRow("select count(*) from %s "+where, args...).Scan(&total); err != nil {
                		return nil, 0, err
                	}
                %s	orderBy := %s
                	rows, err := r.db.Query(`
                		select id, %s, created_at, updated_at
                		from %s `+where+`
                		`+orderBy+`
                %s	`, %s)
                	if err != nil {
                		return nil, 0, err
                	}
                	defer rows.Close()
                	records := make([]%s, 0)
                	for rows.Next() {
                		var item %s
                		if err := rows.Scan(&item.ID, %s, &item.CreatedAt, &item.UpdatedAt); err != nil {
                			return nil, 0, err
                		}
                		records = append(records, item)
                	}
                	return records, total, rows.Err()
                }

                func (r *Repository) scanOne(query string, args ...any) (%s, error) {
                	var item %s
                	err := r.db.QueryRow(query, args...).Scan(&item.ID, %s, &item.CreatedAt, &item.UpdatedAt)
                	return item, err
                }

                func buildWhere(req QueryRequest) (string, []any) {
                %s
                	args := make([]any, 0)
                %s	if len(conditions) == 0 {
                		return "", args
                	}
                	return "where " + strings.Join(conditions, " and "), args
                }
                %s
                """.formatted(
                recipe.packageName(),
                recipe.structName(),
                recipe.tableName(),
                columns,
                placeholders,
                createArgs,
                recipe.structName(),
                recipe.tableName(),
                updateSet,
                updateWhere,
                updateArgs,
                deleteSql,
                recipe.structName(),
                columns,
                recipe.tableName(),
                detailWhere,
                recipe.structName(),
                recipe.tableName(),
                paginationVars,
                orderBy,
                columns,
                recipe.tableName(),
                paginationSql,
                queryArgs,
                recipe.structName(),
                recipe.structName(),
                scanTargets,
                recipe.structName(),
                recipe.structName(),
                scanTargets,
                softDeleteCondition,
                searchCondition,
                sortHelper
        );
    }

    private String backendService(BackendRecipe recipe) {
        String imports = recipe.options().pagination()
                ? """
                import (
                	"errors"
                	"log/slog"

                	"backend-template/internal/domain"
                )
                """
                : """
                import (
                	"errors"
                	"log/slog"
                )
                """;
        String listReturnType = recipe.options().pagination()
                ? "domain.Page[" + recipe.structName() + "VO]"
                : "[]" + recipe.structName() + "VO";
        String normalizeLine = recipe.options().pagination() ? "\treq.Normalize()\n" : "";
        String listErrorReturn = recipe.options().pagination()
                ? "domain.Page[" + recipe.structName() + "VO]{}"
                : "nil";
        String listSuccessReturn = recipe.options().pagination()
                ? "domain.Page[" + recipe.structName() + "VO]{Records: vos, Total: total, Current: req.Current, PageSize: req.PageSize}"
                : "vos";
        String batchDelete = recipe.options().batchActions()
                ? """

                func (s *Service) BatchDelete(ids []int64) error {
                	if len(ids) == 0 {
                		return errors.New("记录 ID 不能为空")
                	}
                	for _, id := range ids {
                		if err := s.Delete(id); err != nil {
                			return err
                		}
                	}
                	return nil
                }
                """
                : "";
        String importExport = recipe.options().importExport()
                ? """

                func (s *Service) Export(req QueryRequest) ([]%sVO, error) {
                	records, _, err := s.repo.List(req)
                	if err != nil {
                		return nil, errors.New("导出%s失败")
                	}
                	vos := make([]%sVO, 0, len(records))
                	for _, item := range records {
                		vos = append(vos, toVO(item))
                	}
                	return vos, nil
                }

                func (s *Service) Import(items []Create%sRequest) (int, error) {
                	count := 0
                	for _, item := range items {
                		if _, err := s.Create(item); err != nil {
                			return count, err
                		}
                		count++
                	}
                	return count, nil
                }
                """.formatted(recipe.structName(), recipe.label(), recipe.structName(), recipe.structName())
                : "";
        return """
                package %s

                %s

                type Service struct {
                	repo *Repository
                }

                func NewService(repo *Repository) *Service {
                	return &Service{repo: repo}
                }

                func (s *Service) Create(req Create%sRequest) (int64, error) {
                	id, err := s.repo.Create(req)
                	if err != nil {
                		slog.Error("create %s failed", "error", err)
                		return 0, errors.New("创建%s失败")
                	}
                	return id, nil
                }

                func (s *Service) Update(req Update%sRequest) error {
                	if req.ID <= 0 {
                		return errors.New("记录 ID 不能为空")
                	}
                	if err := s.repo.Update(req); err != nil {
                		slog.Error("update %s failed", "error", err, "id", req.ID)
                		return errors.New("更新%s失败")
                	}
                	return nil
                }

                func (s *Service) Delete(id int64) error {
                	if id <= 0 {
                		return errors.New("记录 ID 不能为空")
                	}
                	if err := s.repo.Delete(id); err != nil {
                		slog.Error("delete %s failed", "error", err, "id", id)
                		return errors.New("删除%s失败")
                	}
                	return nil
                }

                func (s *Service) Detail(id int64) (%sVO, error) {
                	item, err := s.repo.FindByID(id)
                	if err != nil {
                		return %sVO{}, errors.New("记录不存在")
                	}
                	return toVO(item), nil
                }

                func (s *Service) List(req QueryRequest) (%s, error) {
                %s
                	records, total, err := s.repo.List(req)
                	_ = total
                	if err != nil {
                		slog.Error("list %s failed", "error", err)
                		return %s, errors.New("查询%s失败")
                	}
                	vos := make([]%sVO, 0, len(records))
                	for _, item := range records {
                		vos = append(vos, toVO(item))
                	}
                	return %s, nil
                }
                %s%s

                func toVO(item %s) %sVO {
                	return %sVO(item)
                }
                """.formatted(
                recipe.packageName(),
                imports.stripTrailing(),
                recipe.structName(),
                recipe.packageName(),
                recipe.label(),
                recipe.structName(),
                recipe.packageName(),
                recipe.label(),
                recipe.packageName(),
                recipe.label(),
                recipe.structName(),
                recipe.structName(),
                listReturnType,
                normalizeLine,
                recipe.packageName(),
                listErrorReturn,
                recipe.label(),
                recipe.structName(),
                listSuccessReturn,
                batchDelete,
                importExport,
                recipe.structName(),
                recipe.structName(),
                recipe.structName()
        );
    }

    private String backendHandler(BackendRecipe recipe) {
        String route = "/" + recipe.tableName().replace("_", "-");
        String listRoute = recipe.options().pagination() ? "/list/page" : "/list";
        String optionalRoutes = ""
                + (recipe.options().batchActions() ? "\tmux.HandleFunc(\"POST /api" + route + "/batch-delete\", h.batchDelete)\n" : "")
                + (recipe.options().importExport() ? "\tmux.HandleFunc(\"POST /api" + route + "/import\", h.importItems)\n\tmux.HandleFunc(\"POST /api" + route + "/export\", h.exportItems)\n" : "");
        String authGuard = recipe.options().authRequired() ? """
                	if !requireAuth(w, r) {
                		return
                	}
                """ : "";
        String optionalHandlers = ""
                + (recipe.options().batchActions() ? batchDeleteHandler() : "")
                + (recipe.options().importExport() ? importExportHandlers(recipe) : "")
                + (recipe.options().authRequired() ? authHelper() : "");
        return """
                package %s

                import (
                	"encoding/json"
                	"net/http"
                	"strconv"
                	"strings"

                	"backend-template/internal/response"
                	"backend-template/internal/validator"
                )

                type Handler struct {
                	service *Service
                }

                func NewHandler(service *Service) *Handler {
                	return &Handler{service: service}
                }

                func (h *Handler) RegisterRoutes(mux *http.ServeMux) {
                	mux.HandleFunc("POST /api%s", h.create)
                	mux.HandleFunc("PUT /api%s", h.update)
                	mux.HandleFunc("DELETE /api%s/", h.delete)
                	mux.HandleFunc("GET /api%s/", h.detail)
                	mux.HandleFunc("POST /api%s%s", h.list)
                %s
                	// @AI_INJECT_ROUTE: %s
                }

                func (h *Handler) create(w http.ResponseWriter, r *http.Request) {
                %s
                	var req Create%sRequest
                	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                		response.Error(w, response.CodeBadRequest, "请求参数格式错误")
                		return
                	}
                	if err := validator.ValidateStruct(req); err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	id, err := h.service.Create(req)
                	if err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	response.OK(w, id)
                }

                func (h *Handler) update(w http.ResponseWriter, r *http.Request) {
                %s
                	var req Update%sRequest
                	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                		response.Error(w, response.CodeBadRequest, "请求参数格式错误")
                		return
                	}
                	if err := validator.ValidateStruct(req); err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	if err := h.service.Update(req); err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	response.OK(w, true)
                }

                func (h *Handler) delete(w http.ResponseWriter, r *http.Request) {
                %s
                	id, err := parseID(r.URL.Path)
                	if err != nil {
                		response.Error(w, response.CodeBadRequest, "记录 ID 格式错误")
                		return
                	}
                	if err := h.service.Delete(id); err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	response.OK(w, true)
                }

                func (h *Handler) detail(w http.ResponseWriter, r *http.Request) {
                %s
                	id, err := parseID(r.URL.Path)
                	if err != nil {
                		response.Error(w, response.CodeBadRequest, "记录 ID 格式错误")
                		return
                	}
                	item, err := h.service.Detail(id)
                	if err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	response.OK(w, item)
                }

                func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
                %s
                	var req QueryRequest
                	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                		response.Error(w, response.CodeBadRequest, "请求参数格式错误")
                		return
                	}
                	if err := validator.ValidateStruct(req); err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	page, err := h.service.List(req)
                	if err != nil {
                		response.Error(w, response.CodeInternal, err.Error())
                		return
                	}
                	response.OK(w, page)
                }
                %s

                func parseID(path string) (int64, error) {
                	parts := strings.Split(strings.Trim(path, "/"), "/")
                	return strconv.ParseInt(parts[len(parts)-1], 10, 64)
                }
                """.formatted(
                recipe.packageName(),
                route,
                route,
                route,
                route,
                route,
                listRoute,
                optionalRoutes,
                recipe.packageName(),
                authGuard,
                recipe.structName(),
                authGuard,
                recipe.structName(),
                authGuard,
                authGuard,
                authGuard,
                optionalHandlers
        );
    }

    private String backendSchema(BackendRecipe recipe) {
        StringBuilder fields = new StringBuilder();
        for (RecipeField field : recipe.fields()) {
            fields.append("    ").append(snake(field.name())).append(" ").append(sqlType(field.type()));
            fields.append(sqlDefault(field.type()));
            if (field.required()) {
                fields.append(" not null");
            }
            fields.append(",\n");
        }
        String softDeleteColumn = recipe.options().softDelete()
                ? "    is_deleted integer default 0 not null\n"
                : "    deleted_at timestamp\n";
        String indexes = recipe.indexes().stream()
                .map(column -> "create index if not exists idx_" + recipe.tableName() + "_" + column + " on " + recipe.tableName() + " (" + column + ");")
                .collect(java.util.stream.Collectors.joining("\n"));
        return """

                create table if not exists %s
                (
                    id integer primary key autoincrement,
                %s    created_at timestamp default current_timestamp not null,
                    updated_at timestamp default current_timestamp not null,
                %s
                );

                %s
                """.formatted(recipe.tableName(), fields, softDeleteColumn, indexes);
    }

    private String backendWiring(BackendRecipe recipe) {
        String varPrefix = recipe.packageName();
        return """
                	%sRepo := %s.NewRepository(db)
                	%sService := %s.NewService(%sRepo)
                	%sHandler := %s.NewHandler(%sService)
                	%sHandler.RegisterRoutes(mux)
                """.formatted(
                varPrefix,
                recipe.packageName(),
                varPrefix,
                recipe.packageName(),
                varPrefix,
                varPrefix,
                recipe.packageName(),
                varPrefix,
                varPrefix
        );
    }

    private String batchDeleteHandler() {
        return """

                func (h *Handler) batchDelete(w http.ResponseWriter, r *http.Request) {
                	var req BatchDeleteRequest
                	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                		response.Error(w, response.CodeBadRequest, "请求参数格式错误")
                		return
                	}
                	if err := validator.ValidateStruct(req); err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	if err := h.service.BatchDelete(req.IDs); err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	response.OK(w, true)
                }
                """;
    }

    private String importExportHandlers(BackendRecipe recipe) {
        return """

                func (h *Handler) exportItems(w http.ResponseWriter, r *http.Request) {
                	var req QueryRequest
                	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                		response.Error(w, response.CodeBadRequest, "请求参数格式错误")
                		return
                	}
                	items, err := h.service.Export(req)
                	if err != nil {
                		response.Error(w, response.CodeInternal, err.Error())
                		return
                	}
                	response.OK(w, items)
                }

                func (h *Handler) importItems(w http.ResponseWriter, r *http.Request) {
                	var req []Create%sRequest
                	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                		response.Error(w, response.CodeBadRequest, "请求参数格式错误")
                		return
                	}
                	count, err := h.service.Import(req)
                	if err != nil {
                		response.Error(w, response.CodeBadRequest, err.Error())
                		return
                	}
                	response.OK(w, map[string]any{"count": count})
                }
                """.formatted(recipe.structName());
    }

    private String authHelper() {
        return """

                func requireAuth(w http.ResponseWriter, r *http.Request) bool {
                	token := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
                	if token == "" {
                		response.Error(w, response.CodeUnauthorized, "未登录")
                		return false
                	}
                	return true
                }
                """;
    }

    private List<RecipeField> normalizeFields(List<CreateSpec.FieldSpec> fields) {
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

    private String normalizeFieldType(String type) {
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

    private String goType(String type) {
        return switch (normalizeFieldType(type)) {
            case "integer" -> "int";
            case "decimal" -> "float64";
            case "boolean" -> "bool";
            case "datetime" -> "time.Time";
            default -> "string";
        };
    }

    private String sqlType(String type) {
        return switch (normalizeFieldType(type)) {
            case "integer", "boolean" -> "integer";
            case "decimal" -> "real";
            case "datetime" -> "timestamp";
            default -> "text";
        };
    }

    private String sqlDefault(String type) {
        return switch (normalizeFieldType(type)) {
            case "integer", "boolean" -> " default 0";
            case "decimal" -> " default 0";
            case "datetime" -> " default current_timestamp";
            default -> " default ''";
        };
    }

    private FrontendOptions frontendOptions(CreateSpec spec) {
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
        List<String> styleClasses = styleKeywords.stream().map(this::styleClass).filter(StrUtil::isNotBlank).distinct().toList();
        String surfaceMuted = styleKeywords.stream().anyMatch(value -> containsAny(value, "医疗", "可信")) ? "#ecfeff"
                : styleKeywords.stream().anyMatch(value -> containsAny(value, "教育", "温暖")) ? "#fff7ed"
                : styleKeywords.stream().anyMatch(value -> containsAny(value, "高级", "premium")) ? "#f5f3ff"
                : "#f8fafc";
        return new FrontendOptions(density, styleKeywords, styleClasses, interactions, dataViz, navigation, radius, surfaceMuted);
    }

    private BackendOptions backendOptions(CreateSpec spec) {
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

    private List<String> databaseIndexes(CreateSpec spec, List<RecipeField> fields, String tableName) {
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

    private String normalizeDensity(String density) {
        String normalized = StrUtil.blankToDefault(density, "comfortable").toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "compact", "紧凑", "高信息密度")) return "compact";
        if (containsAny(normalized, "editorial", "内容", "展示")) return "editorial";
        return "comfortable";
    }

    private List<String> normalizeInteraction(List<String> values) {
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

    private List<String> normalizeDataViz(List<String> values) {
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

    private String styleClass(String keyword) {
        if (containsAny(keyword, "运营", "中台", "专业")) return "style-ops";
        if (containsAny(keyword, "高级", "premium")) return "style-premium";
        if (containsAny(keyword, "医疗", "可信")) return "style-medical-trust";
        if (containsAny(keyword, "教育", "温暖")) return "style-education-warm";
        return "";
    }

    private DensityTokens densityTokens(String density) {
        return switch (normalizeDensity(density)) {
            case "compact" -> new DensityTokens("12px", "12px", "40px", "13px", "4px 8px");
            case "editorial" -> new DensityTokens("28px", "24px", "56px", "15px", "8px 12px");
            default -> new DensityTokens("18px", "16px", "48px", "14px", "6px 10px");
        };
    }

    private List<String> filterChips(AdminRecipe recipe) {
        List<String> chips = new ArrayList<>();
        chips.add(recipe.entityLabel() + "状态");
        chips.add(recipe.domain() + "区域");
        if (recipe.frontend().interactions().contains("drawer")) {
            chips.add("详情抽屉");
        }
        if (recipe.frontend().interactions().contains("batch")) {
            chips.add("批量操作");
        }
        return chips;
    }

    private String adminNavItems(AdminRecipe recipe) {
        List<String> nav = recipe.frontend().navigation().isEmpty()
                ? List.of("工作台", recipe.entityLabel() + "管理", "数据分析", "系统设置")
                : recipe.frontend().navigation();
        String items = nav.stream().limit(6)
                .map(label -> "    { label: '" + escape(label) + "', path: '/" + lowerIdentifier(label) + "', icon: 'Menu' }")
                .collect(java.util.stream.Collectors.joining(",\n"));
        return "[\n" + items + "\n  ]";
    }

    private String tsStringArray(List<String> values) {
        return "[" + values.stream().map(value -> "'" + escape(value) + "'").collect(java.util.stream.Collectors.joining(", ")) + "]";
    }

    private String visualizationBlocks(AdminRecipe recipe) {
        List<String> blocks = new ArrayList<>();
        for (String viz : recipe.frontend().dataViz()) {
            switch (viz) {
                case "ranking" -> blocks.add("{ type: 'ranking', title: '" + escape(recipe.entityLabel()) + "排行', range: '本周', values: [88, 76, 69, 61, 52] }");
                case "funnel" -> blocks.add("{ type: 'funnel', title: '转化漏斗', range: '本月', values: [96, 78, 52, 31, 18] }");
                case "calendar" -> blocks.add("{ type: 'calendar', title: '日程热度', range: '近 30 日', values: [32, 48, 73, 45, 67, 82, 58] }");
                default -> blocks.add("{ type: 'trend', title: '业务趋势', range: '近 7 日', values: [42, 68, 54, 88, 74, 96, 82] }");
            }
        }
        return "[\n  " + blocks.stream().distinct().limit(3).collect(java.util.stream.Collectors.joining(",\n  ")) + "\n]";
    }

    private String adminRows(AdminRecipe recipe) {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            StringBuilder row = new StringBuilder("  { no: 'REC-00").append(i).append("'");
            for (RecipeField field : recipe.fields().stream().limit(4).toList()) {
                row.append(", ").append(camel(field.name())).append(": ").append(tsValue(field, i, recipe));
            }
            row.append(", status: '").append(List.of("已完成", "进行中", "待处理", "已完成").get(i - 1)).append("'");
            row.append(", amount: ").append(List.of(12800, 26800, 6900, 52000).get(i - 1));
            row.append(", createTime: '2026-06-10 ").append(List.of("09:30:00", "10:15:00", "11:05:00", "13:20:00").get(i - 1)).append("' }");
            rows.add(row.toString());
        }
        return String.join(",\n", rows);
    }

    private String tsValue(RecipeField field, int index, AdminRecipe recipe) {
        return switch (normalizeFieldType(field.type())) {
            case "integer" -> String.valueOf(20 + index * 8);
            case "decimal" -> String.valueOf(99.0 + index * 30);
            case "boolean" -> index % 2 == 0 ? "true" : "false";
            case "datetime" -> "'2026-06-" + (10 + index) + " 09:00:00'";
            case "enum" -> "'" + (index % 2 == 0 ? "上架" : "下架") + "'";
            default -> "'" + escape(recipe.entityLabel()) + field.label() + index + "'";
        };
    }

    private String safeOrderByFunction(BackendRecipe recipe) {
        String cases = recipe.fields().stream()
                .map(field -> "\tcase \"" + camel(field.name()) + "\":\n\t\tcolumn = \"" + snake(field.name()) + "\"")
                .collect(java.util.stream.Collectors.joining("\n"));
        return """

                func safeOrderBy(req QueryRequest) string {
                	column := "id"
                	switch req.SortBy {
                %s
                	}
                	direction := "desc"
                	if strings.EqualFold(req.SortOrder, "asc") {
                		direction = "asc"
                	}
                	return "order by " + column + " " + direction
                }
                """.formatted(cases);
    }

    private String inferIndustry(String userMessage) {
        String normalized = StrUtil.blankToDefault(userMessage, "").toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "健身", "私教", "瑜伽", "运动")) return "健身运营";
        if (containsAny(normalized, "教育", "课程", "培训")) return "教育培训";
        if (containsAny(normalized, "商品", "订单", "库存", "电商")) return "电商零售";
        if (containsAny(normalized, "客户", "crm", "销售")) return "客户增长";
        return "业务";
    }

    private String inferBrand(String userMessage, String fallback) {
        if (containsAny(userMessage, "健身", "私教")) return "FitPilot";
        if (containsAny(userMessage, "教育", "课程")) return "知行云";
        if (containsAny(userMessage, "商品", "电商")) return "商策云";
        return fallback;
    }

    private String inferEntityName(String userMessage) {
        if (containsAny(userMessage, "课程")) return "Course";
        if (containsAny(userMessage, "商品", "产品")) return "Product";
        if (containsAny(userMessage, "订单")) return "Order";
        if (containsAny(userMessage, "客户")) return "Customer";
        if (containsAny(userMessage, "会员")) return "Member";
        return "Record";
    }

    private String inferEntityLabel(String userMessage) {
        if (containsAny(userMessage, "课程")) return "课程";
        if (containsAny(userMessage, "商品", "产品")) return "商品";
        if (containsAny(userMessage, "订单")) return "订单";
        if (containsAny(userMessage, "客户")) return "客户";
        if (containsAny(userMessage, "会员")) return "会员";
        return "业务记录";
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

    private String readableDomain(String domain) {
        return StrUtil.blankToDefault(domain, "").replace('_', ' ').strip();
    }

    private String firstNonBlank(String value, String fallback) {
        return StrUtil.isBlank(value) ? fallback : value.strip();
    }

    private String validHex(String value, String fallback) {
        String normalized = StrUtil.blankToDefault(value, "").strip();
        return normalized.matches("^#[0-9a-fA-F]{6}$") ? normalized : fallback;
    }

    private String lowerIdentifier(String value) {
        String cleaned = IDENTIFIER_CLEANUP.matcher(StrUtil.blankToDefault(value, "")).replaceAll("_");
        if (StrUtil.isBlank(cleaned)) {
            return "";
        }
        String pascal = pascal(cleaned);
        return pascal.substring(0, 1).toLowerCase(Locale.ROOT) + pascal.substring(1);
    }

    private String pascal(String value) {
        String cleaned = IDENTIFIER_CLEANUP.matcher(StrUtil.blankToDefault(value, "")).replaceAll("_");
        StringBuilder result = new StringBuilder();
        for (String part : cleaned.split("_+")) {
            if (StrUtil.isBlank(part)) {
                continue;
            }
            result.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                result.append(part.substring(1));
            }
        }
        return result.isEmpty() ? "Record" : result.toString();
    }

    private String camel(String value) {
        String pascal = pascal(value);
        return pascal.substring(0, 1).toLowerCase(Locale.ROOT) + pascal.substring(1);
    }

    private String snake(String value) {
        return camel(value).replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private String tableName(String packageName) {
        return snake(packageName) + "s";
    }

    private String fieldLabel(String field) {
        return switch (field) {
            case "name", "title" -> "名称";
            case "coach" -> "教练";
            case "price" -> "价格";
            case "capacity" -> "容量";
            case "owner" -> "负责人";
            case "status" -> "状态";
            case "remark" -> "备注";
            default -> pascal(field);
        };
    }

    private String escape(String value) {
        return StrUtil.blankToDefault(value, "").replace("\\", "\\\\").replace("'", "\\'");
    }

    public record RecipeRenderResult(
            List<String> filledSlots,
            List<PatchOperation> patchOperations,
            int totalChars,
            String summary,
            TemplateVariableManifest manifest
    ) {
        public static RecipeRenderResult empty() {
            return new RecipeRenderResult(List.of(), List.of(), 0, "", null);
        }

        public boolean available() {
            return patchOperations != null && !patchOperations.isEmpty();
        }
    }

    private record AdminRecipe(String brand,
                               String domain,
                               String primary,
                               String accent,
                               String entityLabel,
                               List<RecipeField> fields,
                               FrontendOptions frontend,
                               String mockDataStyle) {
    }

    private record BackendRecipe(String packageName,
                                 String structName,
                                 String label,
                                 String tableName,
                                 List<RecipeField> fields,
                                 BackendOptions options,
                                 List<String> indexes) {
    }

    private record RecipeField(String name,
                               String type,
                               String label,
                               boolean required) {
    }

    private record BasicRecipe(String brand,
                               String headline,
                               String description,
                               String domain,
                               String primary,
                               String accent,
                               String entityLabel,
                               FrontendOptions frontend) {
    }

    private record FrontendOptions(String density,
                                   List<String> styleKeywords,
                                   List<String> styleClasses,
                                   List<String> interactions,
                                   List<String> dataViz,
                                   List<String> navigation,
                                   String radius,
                                   String surfaceMuted) {
    }

    private record BackendOptions(boolean pagination,
                                  boolean search,
                                  boolean sort,
                                  boolean softDelete,
                                  boolean authRequired,
                                  boolean importExport,
                                  boolean batchActions,
                                  boolean validationRequired) {
    }

    private record DensityTokens(String padding,
                                 String gap,
                                 String rowHeight,
                                 String fontSize,
                                 String chipPadding) {
    }
}
