package com.yupi.yuaicodemother.orchestration.recipe;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内置 recipe 库：先覆盖高频应用生成场景，后续可替换为配置化或检索式 recipe。
 */
@Component
public class GenerationRecipeLibrary {

    private static final int MAX_MATCHED_RECIPES = 3;

    private final List<GenerationRecipe> recipes;

    public GenerationRecipeLibrary() {
        this.recipes = List.of(
                recipe(
                        "auth-basic",
                        "登录 / 注册 / 权限",
                        "auth",
                        List.of("登录", "注册", "auth", "login", "signin", "signup", "权限", "角色", "token", "账号", "用户"),
                        List.of("auth", "form", "navigation"),
                        List.of("src/views/Login.vue", "src/views/Register.vue", "src/api", "src/stores", "src/router"),
                        List.of("复用现有路由和登录入口", "补齐表单校验、登录态存储和未授权跳转", "权限逻辑集中在 store、router guard 或 API 适配层"),
                        List.of("验证登录失败提示", "验证登录后跳转", "验证刷新后登录态恢复"),
                        false
                ),
                recipe(
                        "crud-list-search",
                        "CRUD / 列表 / 搜索分页",
                        "management",
                        List.of("crud", "列表", "table", "管理", "搜索", "分页", "新增", "编辑", "删除", "筛选"),
                        List.of("management", "form"),
                        List.of("src/views", "src/pages", "src/components", "src/api"),
                        List.of("先确定列表字段、筛选项和操作列", "新增和编辑共用表单结构", "分页、空状态、加载态和错误态必须成闭环"),
                        List.of("验证搜索条件变化会重置页码", "验证新增/编辑/删除后的列表刷新", "验证空数据展示"),
                        false
                ),
                recipe(
                        "dashboard-analytics",
                        "Dashboard / 图表",
                        "analytics",
                        List.of("dashboard", "工作台", "首页", "概览", "图表", "chart", "统计", "报表", "分析", "指标"),
                        List.of("dashboard", "analytics"),
                        List.of("src/views/Dashboard.vue", "src/pages/home", "src/components", "src/layouts"),
                        List.of("把核心指标、趋势图和最近事件拆成可维护区域", "图表数据先走统一 mock 或 API 适配层", "保证移动端信息密度和可读性"),
                        List.of("验证图表容器有稳定高度", "验证空数据不报错", "验证核心指标与趋势区域同时可见"),
                        false
                ),
                recipe(
                        "form-settings",
                        "表单 / 设置页",
                        "form",
                        List.of("表单", "form", "设置", "setting", "config", "profile", "偏好", "弹窗", "dialog", "modal"),
                        List.of("form", "settings"),
                        List.of("src/views", "src/pages", "src/components", "src/stores"),
                        List.of("表单字段、校验规则和提交状态保持在同一数据模型内", "设置页按分组组织，避免把无关配置混在一个大表单", "提交成功、失败和重置流程都要明确"),
                        List.of("验证必填与格式校验", "验证保存时禁用重复提交", "验证保存失败提示"),
                        false
                ),
                recipe(
                        "database-service",
                        "Database 服务接入",
                        "database",
                        List.of("database", "数据库", "sqlite", "sqllite", "sql lite", "后端", "backend", "api", "接口", "数据服务"),
                        List.of("database", "api", "management"),
                        List.of("backend", "src/api", "src/views", "package.json"),
                        List.of("后端放在独立 backend 目录", "前端通过 HTTP API 调用后端，不把数据库逻辑写进页面", "先完成最小可运行的连接、初始化和示例 API"),
                        List.of("验证后端启动入口存在", "验证前端 API 适配层隔离数据库调用", "验证危险 SQL 不直接暴露给前端"),
                        true
                )
        );
    }

    public List<GenerationRecipe> match(String userMessage, String projectContext) {
        String normalized = normalize(userMessage + "\n" + StrUtil.blankToDefault(projectContext, ""));
        if (StrUtil.isBlank(normalized)) {
            return List.of();
        }
        return recipes.stream()
                .filter(recipe -> recipe.keywords().stream().anyMatch(keyword -> normalized.contains(normalize(keyword))))
                .limit(MAX_MATCHED_RECIPES)
                .toList();
    }

    public List<Map<String, Object>> toPayloads(List<GenerationRecipe> matchedRecipes) {
        if (matchedRecipes == null || matchedRecipes.isEmpty()) {
            return List.of();
        }
        return matchedRecipes.stream()
                .map(GenerationRecipe::toPayload)
                .toList();
    }

    public List<String> modules(List<GenerationRecipe> matchedRecipes) {
        LinkedHashSet<String> modules = new LinkedHashSet<>();
        if (matchedRecipes != null) {
            matchedRecipes.forEach(recipe -> modules.addAll(recipe.modules()));
        }
        return List.copyOf(modules);
    }

    public List<String> contextFileHints(List<GenerationRecipe> matchedRecipes) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (matchedRecipes != null) {
            matchedRecipes.forEach(recipe -> hints.addAll(recipe.contextFileHints()));
        }
        return List.copyOf(hints);
    }

    private GenerationRecipe recipe(String id,
                                    String title,
                                    String intent,
                                    List<String> keywords,
                                    List<String> modules,
                                    List<String> contextFileHints,
                                    List<String> implementationSteps,
                                    List<String> validationHints,
                                    boolean databaseRequired) {
        return new GenerationRecipe(
                id,
                title,
                intent,
                keywords,
                modules,
                contextFileHints,
                implementationSteps,
                validationHints,
                databaseRequired
        );
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "").toLowerCase(Locale.ROOT);
    }
}
