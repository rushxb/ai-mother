package com.yupi.yuaicodemother.orchestration.agent;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.yupi.yuaicodemother.orchestration.recipe.GenerationRecipe;
import com.yupi.yuaicodemother.orchestration.recipe.GenerationRecipeLibrary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 多智能体节点共享的辅助逻辑。
 */
@Component
public class GenerationAgentSupport {

    private static final int MAX_MODEL_CONTEXT_FILE_CHARS = 1400;
    private static final int MAX_SELECTED_CONTEXT_FILES = 6;
    private static final int MAX_CONTEXT_TOTAL_CHARS = 10000;
    private static final Set<String> INDEXABLE_SOURCE_EXTENSIONS = Set.of(
            "vue", "js", "ts", "jsx", "tsx", "css", "scss", "less", "json", "svg", "md", "html", "go", "sql"
    );

    private final GenerationRecipeLibrary recipeLibrary;
    private final WorkspaceSemanticIndexService semanticIndexService;

    public GenerationAgentSupport() {
        this(new GenerationRecipeLibrary(), new WorkspaceSemanticIndexService());
    }

    public GenerationAgentSupport(GenerationRecipeLibrary recipeLibrary) {
        this(recipeLibrary, new WorkspaceSemanticIndexService());
    }

    @Autowired
    public GenerationAgentSupport(GenerationRecipeLibrary recipeLibrary,
                                  WorkspaceSemanticIndexService semanticIndexService) {
        this.recipeLibrary = recipeLibrary;
        this.semanticIndexService = semanticIndexService;
    }

    public boolean isComplexRequest(String userMessage) {
        String normalized = StrUtil.blankToDefault(userMessage, "").toLowerCase(Locale.ROOT);
        return containsAny(normalized,
                "vue", "组件", "路由", "router", "模块", "后台", "管理系统", "登录", "注册",
                "api", "接口", "状态管理", "pinia", "图表", "表单", "多页面", "工作台", "dashboard",
                "crud", "搜索", "分页", "database", "数据库", "sqlite", "后端", "backend");
    }

    public List<String> inferModules(String userMessage, String projectContext) {
        String normalized = (StrUtil.blankToDefault(userMessage, "") + "\n" + StrUtil.blankToDefault(projectContext, "")).toLowerCase(Locale.ROOT);
        List<String> modules = new ArrayList<>();
        if (containsAny(normalized, "登录", "注册", "auth")) {
            modules.add("auth");
        }
        if (containsAny(normalized, "dashboard", "工作台", "首页")) {
            modules.add("dashboard");
        }
        if (containsAny(normalized, "列表", "table", "管理")) {
            modules.add("management");
        }
        if (containsAny(normalized, "图表", "chart", "统计")) {
            modules.add("analytics");
        }
        if (containsAny(normalized, "设置", "setting")) {
            modules.add("settings");
        }
        if (containsAny(normalized, "路由", "router", "menu", "nav", "sidebar", "layout")) {
            modules.add("navigation");
        }
        if (containsAny(normalized, "表单", "form", "input", "dialog", "modal", "editor")) {
            modules.add("form");
        }
        if (containsAny(normalized, "database", "数据库", "sqlite", "sqllite", "sql lite", "后端", "backend", "接口", "api")) {
            modules.add("database");
        }
        modules.addAll(recipeLibrary.modules(matchRecipes(userMessage, projectContext)));
        if (modules.isEmpty()) {
            modules.add("core-app");
        }
        return modules.stream().distinct().toList();
    }

    public List<GenerationRecipe> matchRecipes(String userMessage, String projectContext) {
        return recipeLibrary.match(userMessage, projectContext);
    }

    public List<Map<String, Object>> buildRecipePayloads(List<GenerationRecipe> matchedRecipes) {
        return recipeLibrary.toPayloads(matchedRecipes);
    }

    public String buildProjectContext(App app, CodeGenTypeEnum codeGenTypeEnum, File rootDir) {
        return buildProjectContextPackage(app, codeGenTypeEnum, "", rootDir).projectContext();
    }

    public ProjectContextPackage buildProjectContextPackage(App app,
                                                            CodeGenTypeEnum codeGenTypeEnum,
                                                            String userMessage,
                                                            File rootDir) {
        String intent = inferIntent(userMessage);
        if (rootDir == null || !rootDir.exists() || !rootDir.isDirectory()) {
            return new ProjectContextPackage(intent, List.of(), 0, "empty", "");
        }
        CodeGenTypeEnum resolvedType = codeGenTypeEnum == null ? CodeGenTypeEnum.getEnumByValue(app == null ? null : app.getCodeGenType()) : codeGenTypeEnum;
        if (resolvedType == null) {
            resolvedType = CodeGenTypeEnum.HTML;
        }
        List<String> selectedFiles = normalizeSelectedFiles(selectContextFiles(app, resolvedType, userMessage, rootDir));
        int indexedFileCount = semanticIndexService.countIndexableFiles(rootDir.toPath());
        String contextMode = selectedFiles.isEmpty()
                ? "reuse_index"
                : "general".equals(intent) ? "type_key_files" : "intent_selected_files";
        String projectContext = buildStructuredContext(resolvedType, intent, selectedFiles, indexedFileCount, contextMode, rootDir);
        return new ProjectContextPackage(intent, selectedFiles, indexedFileCount, contextMode, projectContext);
    }

    public List<String> selectContextFiles(App app, CodeGenTypeEnum codeGenTypeEnum, File rootDir) {
        return selectContextFiles(app, codeGenTypeEnum, "", rootDir);
    }

    public List<String> selectContextFiles(App app, CodeGenTypeEnum codeGenTypeEnum, String userMessage, File rootDir) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (rootDir == null || !rootDir.exists()) {
            return List.of();
        }
        String normalizedMessage = buildSearchScope(app, userMessage);
        candidates.addAll(semanticIndexService.suggestFiles(rootDir.toPath(), normalizedMessage, MAX_SELECTED_CONTEXT_FILES));
        recipeLibrary.contextFileHints(matchRecipes(normalizedMessage, "")).forEach(candidates::add);
        if (containsAny(normalizedMessage, "登录", "注册", "auth", "login", "signin", "signup", "用户", "账号", "权限", "角色", "token")) {
            candidates.addAll(List.of("src/views/Login.vue", "src/views/Register.vue", "src/pages/login", "src/pages/register",
                    "src/components/Auth", "src/api", "src/stores", "src/store"));
            candidates.addAll(semanticIndexService.findMatchingFiles(rootDir.toPath(), List.of("login", "register", "auth", "user", "token"), MAX_SELECTED_CONTEXT_FILES));
        }
        if (containsAny(normalizedMessage, "dashboard", "工作台", "首页", "概览", "overview")) {
            candidates.addAll(List.of("src/views/Dashboard.vue", "src/pages/home", "src/layouts", "src/components"));
            candidates.addAll(semanticIndexService.findMatchingFiles(rootDir.toPath(), List.of("dashboard", "home", "overview", "layout"), MAX_SELECTED_CONTEXT_FILES));
        }
        if (containsAny(normalizedMessage, "列表", "table", "管理", "crud", "搜索", "分页")) {
            candidates.addAll(List.of("src/views", "src/pages", "src/components"));
            candidates.addAll(semanticIndexService.findMatchingFiles(rootDir.toPath(), List.of("list", "table", "manage", "management", "crud"), MAX_SELECTED_CONTEXT_FILES));
        }
        if (containsAny(normalizedMessage, "图表", "chart", "统计", "报表", "report", "分析")) {
            candidates.addAll(List.of("src/views", "src/pages", "src/components"));
            candidates.addAll(semanticIndexService.findMatchingFiles(rootDir.toPath(), List.of("chart", "analytics", "stat", "report", "metric"), MAX_SELECTED_CONTEXT_FILES));
        }
        if (containsAny(normalizedMessage, "设置", "setting", "config", "profile", "偏好")) {
            candidates.addAll(List.of("src/views", "src/pages", "src/components"));
            candidates.addAll(semanticIndexService.findMatchingFiles(rootDir.toPath(), List.of("setting", "config", "profile", "preference"), MAX_SELECTED_CONTEXT_FILES));
        }
        if (containsAny(normalizedMessage, "路由", "router", "menu", "nav", "sidebar", "layout")) {
            candidates.addAll(List.of("src/router", "src/layouts", "src/components", "src/views"));
            candidates.addAll(semanticIndexService.findMatchingFiles(rootDir.toPath(), List.of("router", "route", "menu", "nav", "sidebar", "layout"), MAX_SELECTED_CONTEXT_FILES));
        }
        if (containsAny(normalizedMessage, "表单", "form", "input", "dialog", "modal", "editor")) {
            candidates.addAll(List.of("src/views", "src/pages", "src/components"));
            candidates.addAll(semanticIndexService.findMatchingFiles(rootDir.toPath(), List.of("form", "input", "dialog", "modal", "editor"), MAX_SELECTED_CONTEXT_FILES));
        }
        if (containsAny(normalizedMessage, "database", "数据库", "sqlite", "sqllite", "sql lite", "后端", "backend", "接口", "api")) {
            candidates.addAll(List.of("backend", "src/api", "src/views", "src/pages"));
            candidates.addAll(semanticIndexService.findMatchingFiles(rootDir.toPath(), List.of("database", "sqlite", "backend", "api", "service"), MAX_SELECTED_CONTEXT_FILES));
        }

        if (codeGenTypeEnum == CodeGenTypeEnum.HTML) {
            candidates.add("index.html");
        } else if (codeGenTypeEnum == CodeGenTypeEnum.MULTI_FILE) {
            candidates.addAll(List.of("index.html", "style.css", "script.js"));
        } else {
            candidates.addAll(List.of(
                    "package.json",
                    "src/App.vue",
                    "src/main.js",
                    "src/main.ts",
                    "src/router/index.ts",
                    "src/router/index.js",
                    "index.html"
            ));
        }
        return expandCandidates(rootDir, candidates);
    }

    public String buildContextMode(boolean hasGeneratedCode, List<String> selectedFiles) {
        if (!hasGeneratedCode) {
            return "new_project";
        }
        if (selectedFiles == null || selectedFiles.isEmpty()) {
            return "reuse_index";
        }
        return selectedFiles.size() > 4 ? "focused_update" : "minimal_patch";
    }

    private List<String> expandCandidates(File rootDir, LinkedHashSet<String> candidates) {
        List<String> selected = new ArrayList<>();
        for (String candidate : candidates) {
            if (selected.size() >= MAX_SELECTED_CONTEXT_FILES) {
                break;
            }
            File file = new File(rootDir, candidate);
            if (file.exists() && file.isFile()) {
                if (shouldIndex(candidate)) {
                    selected.add(candidate.replace("\\", "/"));
                }
                continue;
            }
            if (file.exists() && file.isDirectory()) {
                selected.addAll(listFilesUnder(rootDir, candidate));
            }
        }
        return selected.stream()
                .filter(StrUtil::isNotBlank)
                .distinct()
                .limit(MAX_SELECTED_CONTEXT_FILES)
                .toList();
    }

    private List<String> listFilesUnder(File rootDir, String relativeDirectory) {
        if (StrUtil.isBlank(relativeDirectory)) {
            return List.of();
        }
        String query = relativeDirectory.replace("\\", "/");
        return semanticIndexService.suggestFiles(rootDir.toPath(), query, MAX_SELECTED_CONTEXT_FILES).stream()
                .filter(path -> path.startsWith(query.endsWith("/") ? query : query + "/"))
                .limit(MAX_SELECTED_CONTEXT_FILES)
                .toList();
    }

    private String buildSearchScope(App app, String userMessage) {
        StringBuilder builder = new StringBuilder();
        builder.append(StrUtil.blankToDefault(userMessage, ""));
        builder.append('\n').append(StrUtil.blankToDefault(app == null ? null : app.getAppName(), ""));
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private String buildStructuredContext(CodeGenTypeEnum codeGenTypeEnum,
                                          String intent,
                                          List<String> selectedFiles,
                                          int indexedFileCount,
                                          String contextMode,
                                          File rootDir) {
        List<String> safeSelectedFiles = selectedFiles == null ? List.of() : selectedFiles;
        StringBuilder builder = new StringBuilder();
        builder.append("上下文模式: ").append(contextMode).append('\n');
        builder.append("意图: ").append(intent).append('\n');
        builder.append("选中文件: ").append(safeSelectedFiles.size()).append(" / ").append(indexedFileCount).append('\n');
        builder.append("生成类型: ").append(codeGenTypeEnum.getValue()).append('\n');
        builder.append('\n');
        if (safeSelectedFiles.isEmpty()) {
            builder.append("未选中可复用文件，保留项目级摘要供模型参考。");
        } else {
            builder.append(readSelectedFileContext(rootDir, safeSelectedFiles));
        }
        if (builder.length() <= MAX_CONTEXT_TOTAL_CHARS) {
            return builder.toString().trim();
        }
        return builder.substring(0, MAX_CONTEXT_TOTAL_CHARS).trim();
    }

    private boolean shouldIndex(String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return false;
        }
        String normalized = relativePath.replace("\\", "/");
        String extension = FileUtil.extName(relativePath).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("src/") || normalized.startsWith("public/") || normalized.startsWith("backend/")) {
            return INDEXABLE_SOURCE_EXTENSIONS.contains(extension);
        }
        return Set.of("package.json", "vite.config.js", "vite.config.ts", "index.html",
                "tsconfig.json", "tsconfig.app.json").contains(normalized);
    }

    private String readSelectedFileContext(File rootDir, List<String> relativePaths) {
        if (relativePaths == null || relativePaths.isEmpty()) {
            return "";
        }
        List<String> sections = new ArrayList<>();
        for (String relativePath : relativePaths) {
            File file = new File(rootDir, relativePath);
            if (!file.exists() || !file.isFile()) {
                continue;
            }
            String extension = FileUtil.extName(file);
            try {
                String content = FileUtil.readString(file, StandardCharsets.UTF_8);
                sections.add("当前文件: " + relativePath + "\n```" + extension + "\n" + truncate(content) + "\n```");
            } catch (Exception ignored) {
            }
        }
        return String.join("\n\n", sections);
    }

    public List<String> normalizeSelectedFiles(List<String> selectedFiles) {
        if (selectedFiles == null || selectedFiles.isEmpty()) {
            return List.of();
        }
        return selectedFiles.stream()
                .filter(StrUtil::isNotBlank)
                .map(path -> path.replace("\\", "/"))
                .filter(path -> !path.contains(".."))
                .filter(path -> !path.startsWith("/"))
                .distinct()
                .limit(MAX_SELECTED_CONTEXT_FILES)
                .collect(Collectors.toList());
    }

    private String truncate(String content) {
        if (content == null || content.length() <= MAX_MODEL_CONTEXT_FILE_CHARS) {
            return StrUtil.blankToDefault(content, "");
        }
        return content.substring(0, MAX_MODEL_CONTEXT_FILE_CHARS)
                + "\n<!-- 文件内容过长，以上为截断后的前 "
                + MAX_MODEL_CONTEXT_FILE_CHARS
                + " 个字符 -->";
    }

    private String inferIntent(String userMessage) {
        String normalized = StrUtil.blankToDefault(userMessage, "").toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "登录", "注册", "auth", "login", "signin", "signup", "用户", "账号", "权限", "角色", "token")) {
            return "auth";
        }
        if (containsAny(normalized, "dashboard", "工作台", "首页", "概览", "overview")) {
            return "dashboard";
        }
        if (containsAny(normalized, "列表", "table", "管理", "crud", "搜索", "分页")) {
            return "management";
        }
        if (containsAny(normalized, "图表", "chart", "统计", "报表", "report", "分析")) {
            return "analytics";
        }
        if (containsAny(normalized, "设置", "setting", "config", "profile", "偏好")) {
            return "settings";
        }
        if (containsAny(normalized, "路由", "router", "menu", "nav", "sidebar", "layout")) {
            return "navigation";
        }
        if (containsAny(normalized, "表单", "form", "input", "dialog", "modal", "editor")) {
            return "form";
        }
        if (containsAny(normalized, "database", "数据库", "sqlite", "sqllite", "sql lite", "后端", "backend", "接口", "api")) {
            return "database";
        }
        return "general";
    }

    public record ProjectContextPackage(
            String intent,
            List<String> selectedFiles,
            int indexedFileCount,
            String contextMode,
            String projectContext
    ) {
    }

    private boolean containsAny(String value, String... keywords) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
