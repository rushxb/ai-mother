package com.yupi.yuaicodemother.orchestration.agent;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
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
            "vue", "js", "ts", "jsx", "tsx", "css", "scss", "less", "json", "svg", "md", "html"
    );
    private static final Set<String> SKIPPED_WALK_DIRECTORIES = Set.of(
            ".git", ".idea", ".vscode", "node_modules", "dist", "target", "coverage", "build", "out", ".cache", ".turbo"
    );

    public boolean isComplexRequest(String userMessage) {
        String normalized = StrUtil.blankToDefault(userMessage, "").toLowerCase(Locale.ROOT);
        return containsAny(normalized,
                "vue", "组件", "路由", "router", "模块", "后台", "管理系统", "登录", "注册",
                "api", "接口", "状态管理", "pinia", "图表", "表单", "多页面", "工作台", "dashboard");
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
        if (modules.isEmpty()) {
            modules.add("core-app");
        }
        return modules.stream().distinct().toList();
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
        int indexedFileCount = countIndexableFiles(rootDir);
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
        if (containsAny(normalizedMessage, "登录", "注册", "auth", "login", "signin", "signup", "用户", "账号", "权限", "角色", "token")) {
            candidates.addAll(List.of("src/views/Login.vue", "src/views/Register.vue", "src/pages/login", "src/pages/register",
                    "src/components/Auth", "src/api", "src/stores", "src/store"));
            candidates.addAll(findMatchingFiles(rootDir, List.of("login", "register", "auth", "user", "token")));
        }
        if (containsAny(normalizedMessage, "dashboard", "工作台", "首页", "概览", "overview")) {
            candidates.addAll(List.of("src/views/Dashboard.vue", "src/pages/home", "src/layouts", "src/components"));
            candidates.addAll(findMatchingFiles(rootDir, List.of("dashboard", "home", "overview", "layout")));
        }
        if (containsAny(normalizedMessage, "列表", "table", "管理", "crud", "搜索", "分页")) {
            candidates.addAll(List.of("src/views", "src/pages", "src/components"));
            candidates.addAll(findMatchingFiles(rootDir, List.of("list", "table", "manage", "management", "crud")));
        }
        if (containsAny(normalizedMessage, "图表", "chart", "统计", "报表", "report", "分析")) {
            candidates.addAll(List.of("src/views", "src/pages", "src/components"));
            candidates.addAll(findMatchingFiles(rootDir, List.of("chart", "analytics", "stat", "report", "metric")));
        }
        if (containsAny(normalizedMessage, "设置", "setting", "config", "profile", "偏好")) {
            candidates.addAll(List.of("src/views", "src/pages", "src/components"));
            candidates.addAll(findMatchingFiles(rootDir, List.of("setting", "config", "profile", "preference")));
        }
        if (containsAny(normalizedMessage, "路由", "router", "menu", "nav", "sidebar", "layout")) {
            candidates.addAll(List.of("src/router", "src/layouts", "src/components", "src/views"));
            candidates.addAll(findMatchingFiles(rootDir, List.of("router", "route", "menu", "nav", "sidebar", "layout")));
        }
        if (containsAny(normalizedMessage, "表单", "form", "input", "dialog", "modal", "editor")) {
            candidates.addAll(List.of("src/views", "src/pages", "src/components"));
            candidates.addAll(findMatchingFiles(rootDir, List.of("form", "input", "dialog", "modal", "editor")));
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
                    selected.add(candidate);
                }
                continue;
            }
            if (file.exists() && file.isDirectory()) {
                List<String> discovered = listFilesUnder(rootDir, file);
                for (String relative : discovered) {
                    if (selected.size() >= MAX_SELECTED_CONTEXT_FILES) {
                        break;
                    }
                    selected.add(relative);
                }
            }
        }
        return selected.stream()
                .filter(StrUtil::isNotBlank)
                .distinct()
                .limit(MAX_SELECTED_CONTEXT_FILES)
                .toList();
    }

    private List<String> listFilesUnder(File rootDir, File directory) {
        List<String> selected = new ArrayList<>();
        walkProjectFiles(rootDir, directory, file -> {
            if (file.isFile()) {
                String relativePath = relativize(rootDir, file);
                if (shouldIndex(relativePath)) {
                    selected.add(relativePath);
                }
            }
            return selected.size() < MAX_SELECTED_CONTEXT_FILES;
        });
        return selected;
    }

    private List<String> findMatchingFiles(File rootDir, List<String> keywords) {
        List<String> matches = new ArrayList<>();
        walkProjectFiles(rootDir, rootDir, file -> {
            if (!file.isFile()) {
                return true;
            }
            String relativePath = relativize(rootDir, file);
            String normalizedPath = relativePath.toLowerCase(Locale.ROOT);
            if (shouldIndex(relativePath) && keywords.stream().anyMatch(normalizedPath::contains)) {
                matches.add(relativePath);
            }
            return matches.size() < MAX_SELECTED_CONTEXT_FILES;
        });
        return matches;
    }

    private String buildSearchScope(App app, String userMessage) {
        StringBuilder builder = new StringBuilder();
        builder.append(StrUtil.blankToDefault(userMessage, ""));
        builder.append('\n').append(StrUtil.blankToDefault(app == null ? null : app.getAppName(), ""));
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private int countIndexableFiles(File rootDir) {
        int[] count = {0};
        walkProjectFiles(rootDir, rootDir, file -> {
            String relativePath = relativize(rootDir, file);
            if (shouldIndex(relativePath)) {
                count[0]++;
            }
            return true;
        });
        return count[0];
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
        if (normalized.startsWith("src/") || normalized.startsWith("public/")) {
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

    private String relativize(File rootDir, File file) {
        Path rootPath = rootDir.toPath().toAbsolutePath().normalize();
        Path filePath = file.toPath().toAbsolutePath().normalize();
        return rootPath.relativize(filePath).toString().replace(File.separator, "/");
    }

    private void walkProjectFiles(File rootDir, File startDir, Predicate<File> visitor) {
        if (rootDir == null || startDir == null || !startDir.exists()) {
            return;
        }
        Path rootPath = rootDir.toPath().toAbsolutePath().normalize();
        Path startPath = startDir.toPath().toAbsolutePath().normalize();
        if (!startPath.startsWith(rootPath)) {
            return;
        }
        try {
            Files.walkFileTree(startPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(startPath)) {
                        String directoryName = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        if (SKIPPED_WALK_DIRECTORIES.contains(directoryName)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    return visitor.test(file.toFile()) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                }
            });
        } catch (Exception ignored) {
        }
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
