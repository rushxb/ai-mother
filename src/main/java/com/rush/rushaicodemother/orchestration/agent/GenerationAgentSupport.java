package com.rush.rushaicodemother.orchestration.agent;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.context.GeneratedProjectContextService;
import com.rush.rushaicodemother.orchestration.context.GeneratedProjectContextService.ProjectFileContext;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndex;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticSearchHit;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipe;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipeLibrary;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkill;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkillLibrary;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.GenerationContextCompressionService;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 多智能体节点共享的辅助逻辑。
 */
public class GenerationAgentSupport {

    private static final int MAX_SELECTED_CONTEXT_FILES = 6;
    private static final Set<String> INDEXABLE_SOURCE_EXTENSIONS = Set.of(
            "vue", "js", "ts", "jsx", "tsx", "css", "scss", "less", "json", "svg", "md", "html", "java", "xml", "yml", "yaml", "go", "sql"
    );

    private final GenerationRecipeLibrary recipeLibrary;
    private final GenerationSkillLibrary skillLibrary;
    private final WorkspaceSemanticIndexService semanticIndexService;
    private final GenerationContextCompressionService contextCompressionService;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GeneratedProjectContextService generatedProjectContextService;

    /**
 * 创建生成智能体支持实例并完成必要的依赖和初始状态设置。
 *
 * @param recipeLibrary {@code recipeLibrary} 对应的调用参数
 * @param skillLibrary {@code skillLibrary} 对应的调用参数
 * @param semanticIndexService 语义索引服务
 * @param contextCompressionService 上下文压缩服务
 * @param generationWorkspaceService 生成工作区服务
 * @param generatedProjectContextService 处理该职责的领域服务
 */
    public GenerationAgentSupport(
            GenerationRecipeLibrary recipeLibrary,
            GenerationSkillLibrary skillLibrary,
            WorkspaceSemanticIndexService semanticIndexService,
            GenerationContextCompressionService contextCompressionService,
            GenerationWorkspaceService generationWorkspaceService,
            GeneratedProjectContextService generatedProjectContextService
    ) {
        this.recipeLibrary = Objects.requireNonNull(recipeLibrary, "recipeLibrary must not be null");
        this.skillLibrary = Objects.requireNonNull(skillLibrary, "skillLibrary must not be null");
        this.semanticIndexService = Objects.requireNonNull(
                semanticIndexService,
                "semanticIndexService must not be null"
        );
        this.contextCompressionService = Objects.requireNonNull(
                contextCompressionService,
                "contextCompressionService must not be null"
        );
        this.generationWorkspaceService = Objects.requireNonNull(
                generationWorkspaceService,
                "generationWorkspaceService must not be null"
        );
        this.generatedProjectContextService = Objects.requireNonNull(
                generatedProjectContextService,
                "生成项目上下文服务不能为空"
        );
    }

    public List<GenerationRecipe> matchRecipes(String userMessage, String projectContext) {
        return recipeLibrary.match(userMessage, projectContext);
    }

    public List<Map<String, Object>> buildRecipePayloads(List<GenerationRecipe> matchedRecipes) {
        return recipeLibrary.toPayloads(matchedRecipes);
    }

    public List<GenerationSkill> matchSkills(String userMessage) {
        return skillLibrary.match(userMessage);
    }

    public List<Map<String, Object>> buildSkillPayloads(List<GenerationSkill> matchedSkills) {
        return skillLibrary.toPayloads(matchedSkills);
    }

    /**
 * 构建并返回项目上下文。
 *
 * @param app 应用
 * @param codeGenTypeEnum {@code codeGenTypeEnum} 对应的调用参数
 * @param rootDir {@code rootDir} 对应的调用参数
 * @return 处理后的项目上下文文本
 */
    public String buildProjectContext(App app, CodeGenTypeEnum codeGenTypeEnum, File rootDir) {
        return buildProjectContextPackage(app, codeGenTypeEnum, "", rootDir).projectContext();
    }

    /**
 * 根据当前上下文解析工作区根。
 *
 * @param app 应用
 * @return 工作区根
 */
    public File resolveWorkspaceRoot(App app) {
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(app == null ? null : app.getCodeGenType());
        return resolveWorkspaceRoot(app, codeGenTypeEnum);
    }

    /**
 * 根据当前上下文解析工作区根。
 *
 * @param app 应用
 * @param codeGenTypeEnum {@code codeGenTypeEnum} 对应的调用参数
 * @return 工作区根
 */
    public File resolveWorkspaceRoot(App app, CodeGenTypeEnum codeGenTypeEnum) {
        if (app == null || app.getId() == null || app.getId() <= 0) {
            return null;
        }
        CodeGenTypeEnum resolvedType = codeGenTypeEnum == null
                ? CodeGenTypeEnum.getEnumByValue(app.getCodeGenType())
                : codeGenTypeEnum;
        if (resolvedType == null) {
            return null;
        }
        GenerationWorkspace workspace = generationWorkspaceService.resolve(app.getId(), resolvedType);
        return workspace.exists() ? workspace.canonicalRootPath().toFile() : null;
    }

    /**
 * 采集并汇总索引{@code Recall}{@code Payloads}。
 *
 * @param app 应用
 * @param userMessage 用户消息
 * @param limit 资源上限
 * @return 索引{@code Recall}{@code Payloads}集合
 */
    public List<Map<String, Object>> collectIndexRecallPayloads(App app, String userMessage, int limit) {
        return collectProjectIndexRecall(app, userMessage, limit).indexHits();
    }

    /**
 * 采集并汇总项目索引{@code Recall}。
 *
 * @param app 应用
 * @param userMessage 用户消息
 * @param limit 资源上限
 * @return 项目索引{@code Recall}
 */
    public ProjectIndexRecall collectProjectIndexRecall(App app, String userMessage, int limit) {
        File rootDir = resolveWorkspaceRoot(app);
        if (rootDir == null) {
            return new ProjectIndexRecall(null, List.of());
        }
        WorkspaceSemanticIndex index = semanticIndexService.loadOrBuild(rootDir.toPath());
        List<Map<String, Object>> indexHits = collectIndexRecallPayloads(
                index,
                buildSearchScope(app, userMessage),
                limit,
                List.of()
        );
        return new ProjectIndexRecall(index, indexHits);
    }

    public ProjectContextPackage buildProjectContextPackage(App app,
                                                            CodeGenTypeEnum codeGenTypeEnum,
                                                            String userMessage,
                                                            File rootDir) {
        return buildProjectContextPackage(app, codeGenTypeEnum, userMessage, rootDir, null);
    }

    /**
 * 构建并返回项目上下文依赖包。
 *
 * @param app 应用
 * @param codeGenTypeEnum {@code codeGenTypeEnum} 对应的调用参数
 * @param userMessage 用户消息
 * @param rootDir {@code rootDir} 对应的调用参数
 * @param indexSnapshot 索引快照
 * @return 项目上下文依赖包
 */
    public ProjectContextPackage buildProjectContextPackage(App app,
                                                            CodeGenTypeEnum codeGenTypeEnum,
                                                            String userMessage,
                                                            File rootDir,
                                                            WorkspaceSemanticIndex indexSnapshot) {
        return buildProjectContextPackage(
                app,
                codeGenTypeEnum,
                userMessage,
                rootDir,
                indexSnapshot,
                resolveGuidanceContextFileHints(userMessage)
        );
    }

    /** 使用已冻结的工程指引提示构建项目上下文，避免 Agent 再次匹配 recipe/skill。 */
    public ProjectContextPackage buildProjectContextPackage(
            App app,
            CodeGenTypeEnum codeGenTypeEnum,
            String userMessage,
            File rootDir,
            WorkspaceSemanticIndex indexSnapshot,
            List<String> guidanceContextFileHints
    ) {
        String intent = inferIntent(userMessage);
        if (rootDir == null || !rootDir.exists() || !rootDir.isDirectory()) {
            return new ProjectContextPackage(
                    intent, List.of(), 0, 0, List.of(), "empty", "", List.of());
        }
        CodeGenTypeEnum resolvedType = codeGenTypeEnum == null ? CodeGenTypeEnum.getEnumByValue(app == null ? null : app.getCodeGenType()) : codeGenTypeEnum;
        if (resolvedType == null) {
            resolvedType = CodeGenTypeEnum.HTML;
        }
        WorkspaceSemanticIndex index = resolveIndexSnapshot(rootDir, indexSnapshot);
        List<String> selectedFiles = normalizeSelectedFiles(
                selectContextFiles(
                        app,
                        resolvedType,
                        userMessage,
                        rootDir,
                        index,
                        guidanceContextFileHints
                ));
        int indexedFileCount = semanticIndexService.indexedFileCount(index);
        int indexedSymbolCount = semanticIndexService.indexedSymbolCount(index);
        List<Map<String, Object>> indexHits = collectIndexRecallPayloads(
                index,
                buildSearchScope(app, userMessage),
                MAX_SELECTED_CONTEXT_FILES,
                selectedFiles
        );
        String contextMode = selectedFiles.isEmpty()
                ? "reuse_index"
                : "general".equals(intent) ? "type_key_files" : "intent_selected_files";
        List<ProjectFileContext> projectFiles = generatedProjectContextService.readSelectedFiles(
                rootDir.toPath(), selectedFiles);
        String projectContext = buildStructuredContext(
                resolvedType,
                intent,
                selectedFiles,
                indexedFileCount,
                indexedSymbolCount,
                indexHits,
                contextMode,
                projectFiles
        );
        return new ProjectContextPackage(
                intent, selectedFiles, indexedFileCount, indexedSymbolCount,
                indexHits, contextMode, projectContext, projectFiles);
    }

    /** 根据当前上下文解析索引快照。 */
    private WorkspaceSemanticIndex resolveIndexSnapshot(File rootDir, WorkspaceSemanticIndex indexSnapshot) {
        Path normalizedRoot = rootDir.toPath().toAbsolutePath().normalize();
        if (indexSnapshot != null && StrUtil.isNotBlank(indexSnapshot.rootPath())) {
            try {
                if (normalizedRoot.equals(Path.of(indexSnapshot.rootPath()).toAbsolutePath().normalize())) {
                    return indexSnapshot;
                }
            } catch (RuntimeException ignored) {
                // 非法快照身份不能影响当前工作区重新建索引。
            }
        }
        return semanticIndexService.loadOrBuild(normalizedRoot);
    }

    public List<String> selectContextFiles(App app, CodeGenTypeEnum codeGenTypeEnum, File rootDir) {
        return selectContextFiles(app, codeGenTypeEnum, "", rootDir);
    }

    /**
 * 从候选项中选择上下文文件。
 *
 * @param app 应用
 * @param codeGenTypeEnum {@code codeGenTypeEnum} 对应的调用参数
 * @param userMessage 用户消息
 * @param rootDir {@code rootDir} 对应的调用参数
 * @return 上下文文件集合
 */
    public List<String> selectContextFiles(App app, CodeGenTypeEnum codeGenTypeEnum, String userMessage, File rootDir) {
        if (rootDir == null || !rootDir.exists()) {
            return List.of();
        }
        WorkspaceSemanticIndex index = semanticIndexService.loadOrBuild(rootDir.toPath());
        return selectContextFiles(
                app,
                codeGenTypeEnum,
                userMessage,
                rootDir,
                index,
                resolveGuidanceContextFileHints(userMessage)
        );
    }

    /** 从候选项中选择上下文文件。 */
    private List<String> selectContextFiles(App app,
                                            CodeGenTypeEnum codeGenTypeEnum,
                                            String userMessage,
                                            File rootDir,
                                            WorkspaceSemanticIndex index,
                                            List<String> guidanceContextFileHints) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String normalizedMessage = buildSearchScope(app, userMessage);
        candidates.addAll(semanticIndexService.suggestFilesFromSnapshot(
                index, normalizedMessage, MAX_SELECTED_CONTEXT_FILES));
        if (guidanceContextFileHints != null) {
            guidanceContextFileHints.stream()
                    .filter(StrUtil::isNotBlank)
                    .forEach(candidates::add);
        }
        if (containsAny(normalizedMessage, "登录", "注册", "auth", "login", "signin", "signup", "用户", "账号", "权限", "角色", "token")) {
            candidates.addAll(List.of("src/views/Login.vue", "src/views/Register.vue", "src/pages/login", "src/pages/register",
                    "src/components/Auth", "src/api", "src/stores", "src/store"));
            candidates.addAll(semanticIndexService.findMatchingFilesFromSnapshot(index, List.of("login", "register", "auth", "user", "token"), MAX_SELECTED_CONTEXT_FILES));
        }
        if (containsAny(normalizedMessage, "dashboard", "工作台", "首页", "概览", "overview")) {
            candidates.addAll(List.of("src/views/Dashboard.vue", "src/pages/home", "src/layouts", "src/components"));
            candidates.addAll(semanticIndexService.findMatchingFilesFromSnapshot(index, List.of("dashboard", "home", "overview", "layout"), MAX_SELECTED_CONTEXT_FILES));
        }
        if (containsAny(normalizedMessage, "列表", "table", "管理", "crud", "搜索", "分页")) {
            candidates.addAll(List.of("src/views", "src/pages", "src/components"));
            candidates.addAll(semanticIndexService.findMatchingFilesFromSnapshot(index, List.of("list", "table", "manage", "management", "crud"), MAX_SELECTED_CONTEXT_FILES));
        }
        if (containsAny(normalizedMessage, "图表", "chart", "统计", "报表", "report", "分析")) {
            candidates.addAll(List.of("src/views", "src/pages", "src/components"));
            candidates.addAll(semanticIndexService.findMatchingFilesFromSnapshot(index, List.of("chart", "analytics", "stat", "report", "metric"), MAX_SELECTED_CONTEXT_FILES));
        }
        if (containsAny(normalizedMessage, "设置", "setting", "config", "profile", "偏好")) {
            candidates.addAll(List.of("src/views", "src/pages", "src/components"));
            candidates.addAll(semanticIndexService.findMatchingFilesFromSnapshot(index, List.of("setting", "config", "profile", "preference"), MAX_SELECTED_CONTEXT_FILES));
        }
        if (containsAny(normalizedMessage, "路由", "router", "menu", "nav", "sidebar", "layout")) {
            candidates.addAll(List.of("src/router", "src/layouts", "src/components", "src/views"));
            candidates.addAll(semanticIndexService.findMatchingFilesFromSnapshot(index, List.of("router", "route", "menu", "nav", "sidebar", "layout"), MAX_SELECTED_CONTEXT_FILES));
        }
        if (containsAny(normalizedMessage, "表单", "form", "input", "dialog", "modal", "editor")) {
            candidates.addAll(List.of("src/views", "src/pages", "src/components"));
            candidates.addAll(semanticIndexService.findMatchingFilesFromSnapshot(index, List.of("form", "input", "dialog", "modal", "editor"), MAX_SELECTED_CONTEXT_FILES));
        }
        if (containsAny(normalizedMessage, "database", "数据库", "sqlite", "sqllite", "sql lite", "后端", "backend", "接口", "api")) {
            candidates.addAll(List.of("cmd/server", "internal", "sql", "go.mod"));
            candidates.addAll(semanticIndexService.findMatchingFilesFromSnapshot(index, List.of("database", "sqlite", "backend", "api", "service", "handler", "repository"), MAX_SELECTED_CONTEXT_FILES));
        }

        if (codeGenTypeEnum == CodeGenTypeEnum.HTML) {
            candidates.add("index.html");
        } else if (codeGenTypeEnum == CodeGenTypeEnum.MULTI_FILE) {
            candidates.addAll(List.of("index.html", "style.css", "script.js"));
        } else if (codeGenTypeEnum == CodeGenTypeEnum.BACKEND_PROJECT) {
            candidates.addAll(List.of(
                    "go.mod",
                    "cmd/server/main.go",
                    "internal/config/config.go",
                    "internal/database/database.go",
                    "sql/schema.sql"
            ));
        } else if (codeGenTypeEnum == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            candidates.addAll(List.of(
                    "frontend/package.json",
                    "frontend/src/services/request.ts",
                    "frontend/src/App.vue",
                    "backend/go.mod",
                    "backend/cmd/server/main.go",
                    "backend/internal/config/config.go",
                    "backend/sql/schema.sql",
                    ".env.example"
            ));
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
        return expandCandidates(rootDir, candidates, index);
    }

    private List<String> resolveGuidanceContextFileHints(String userMessage) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        hints.addAll(recipeLibrary.contextFileHints(recipeLibrary.match(userMessage, "")));
        hints.addAll(skillLibrary.contextFileHints(skillLibrary.match(userMessage)));
        return List.copyOf(hints);
    }

    /**
 * 构建并返回上下文模式。
 *
 * @param hasGeneratedCode {@code hasGeneratedCode} 对应的调用参数
 * @param selectedFiles 待处理的 {@code selectedFiles} 集合
 * @return 处理后的上下文模式文本
 */
    public String buildContextMode(boolean hasGeneratedCode, List<String> selectedFiles) {
        if (!hasGeneratedCode) {
            return "new_project";
        }
        if (selectedFiles == null || selectedFiles.isEmpty()) {
            return "reuse_index";
        }
        return selectedFiles.size() > 4 ? "focused_update" : "minimal_patch";
    }

    /** 返回{@code expand}{@code Candidates}。 */
    private List<String> expandCandidates(File rootDir,
                                          LinkedHashSet<String> candidates,
                                          WorkspaceSemanticIndex index) {
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
                selected.addAll(listFilesUnder(index, candidate));
            }
        }
        return selected.stream()
                .filter(StrUtil::isNotBlank)
                .distinct()
                .limit(MAX_SELECTED_CONTEXT_FILES)
                .toList();
    }

    private List<String> listFilesUnder(WorkspaceSemanticIndex index, String relativeDirectory) {
        if (StrUtil.isBlank(relativeDirectory)) {
            return List.of();
        }
        String query = relativeDirectory.replace("\\", "/");
        return semanticIndexService.suggestFilesFromSnapshot(index, query, MAX_SELECTED_CONTEXT_FILES).stream()
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

    /** 构建并返回{@code Structured}上下文。 */
    private String buildStructuredContext(CodeGenTypeEnum codeGenTypeEnum,
                                          String intent,
                                          List<String> selectedFiles,
                                          int indexedFileCount,
                                          int indexedSymbolCount,
                                          List<Map<String, Object>> indexHits,
                                          String contextMode,
                                          List<ProjectFileContext> projectFiles) {
        List<String> safeSelectedFiles = selectedFiles == null ? List.of() : selectedFiles;
        StringBuilder builder = new StringBuilder();
        builder.append("上下文模式: ").append(contextMode).append('\n');
        builder.append("意图: ").append(intent).append('\n');
        builder.append("选中文件: ").append(safeSelectedFiles.size()).append(" / ").append(indexedFileCount).append('\n');
        builder.append("索引符号: ").append(indexedSymbolCount).append('\n');
        builder.append("生成类型: ").append(codeGenTypeEnum.getValue()).append('\n');
        builder.append('\n');
        appendIndexHits(builder, indexHits);
        if (safeSelectedFiles.isEmpty()) {
            builder.append("未选中可复用文件，保留项目级摘要供模型参考。");
        } else {
            builder.append(generatedProjectContextService.buildSelectedFileSections(
                    projectFiles, builder.length()));
        }
        return compressProjectContext(generatedProjectContextService.boundAssembledContext(
                builder.toString().trim()));
    }

    /** 判断是否应执行索引。 */
    private boolean shouldIndex(String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return false;
        }
        String normalized = relativePath.replace("\\", "/");
        String extension = FileUtil.extName(relativePath).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("src/") || normalized.startsWith("public/") || normalized.startsWith("cmd/") || normalized.startsWith("internal/") || normalized.startsWith("backend/") || normalized.startsWith("frontend/") || normalized.startsWith("sql/")) {
            return INDEXABLE_SOURCE_EXTENSIONS.contains(extension);
        }
        return Set.of("package.json", "vite.config.js", "vite.config.ts", "index.html",
                "tsconfig.json", "tsconfig.app.json", "go.mod", "go.sum", "README.md").contains(normalized);
    }

    /** 采集并汇总索引{@code Recall}{@code Payloads}。 */
    private List<Map<String, Object>> collectIndexRecallPayloads(WorkspaceSemanticIndex index,
                                                                 String query,
                                                                 int limit,
                                                                 List<String> selectedFiles) {
        if (index == null || StrUtil.isBlank(query)) {
            return List.of();
        }
        Set<String> selectedFileSet = selectedFiles == null ? Set.of() : selectedFiles.stream()
                .filter(StrUtil::isNotBlank)
                .map(path -> path.replace("\\", "/"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return semanticIndexService.searchSnapshot(index, query, Set.of(), limit).stream()
                .filter(hit -> selectedFileSet.isEmpty() || selectedFileSet.contains(hit.relativePath()))
                .map(this::toIndexHitPayload)
                .collect(Collectors.collectingAndThen(
                        Collectors.toList(),
                        hits -> mergeSelectedFileHits(index, selectedFiles, hits)))
                .stream()
                .limit(limit)
                .toList();
    }

    /** 合并{@code Selected}文件{@code Hits}。 */
    private List<Map<String, Object>> mergeSelectedFileHits(WorkspaceSemanticIndex index,
                                                            List<String> selectedFiles,
                                                            List<Map<String, Object>> hits) {
        if (selectedFiles == null || selectedFiles.isEmpty()) {
            return hits;
        }
        LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> hit : hits) {
            merged.put(String.valueOf(hit.get("relativePath")), hit);
        }
        List<String> missingSelectedFiles = selectedFiles.stream()
                .filter(StrUtil::isNotBlank)
                .map(path -> path.replace("\\", "/"))
                .filter(path -> !merged.containsKey(path))
                .toList();
        if (!missingSelectedFiles.isEmpty()) {
            semanticIndexService.describeFilesFromSnapshot(index, missingSelectedFiles).stream()
                    .map(this::toIndexHitPayload)
                    .forEach(hit -> merged.putIfAbsent(String.valueOf(hit.get("relativePath")), hit));
        }
        return new ArrayList<>(merged.values());
    }

    private Map<String, Object> toIndexHitPayload(WorkspaceSemanticSearchHit hit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("relativePath", hit.relativePath());
        payload.put("fileName", hit.fileName());
        payload.put("matchType", hit.matchType());
        payload.put("score", hit.score());
        payload.put("recallSource", hit.recallSource());
        payload.put("matchedTerms", hit.matchedTerms());
        payload.put("matchedSymbols", hit.matchedSymbols());
        return payload;
    }

    /** 追加索引{@code Hits}。 */
    private void appendIndexHits(StringBuilder builder, List<Map<String, Object>> indexHits) {
        if (indexHits == null || indexHits.isEmpty()) {
            return;
        }
        builder.append("索引命中:\n");
        for (Map<String, Object> hit : indexHits.stream().limit(3).toList()) {
            builder.append("- ")
                    .append(hit.get("relativePath"))
                    .append(" [")
                    .append(hit.get("matchType"))
                    .append(", score=")
                    .append(hit.get("score"))
                    .append("]");
            Object matchedSymbols = hit.get("matchedSymbols");
            if (matchedSymbols instanceof List<?> symbols && !symbols.isEmpty()) {
                builder.append(" symbols=").append(symbols.stream().limit(5).toList());
            }
            builder.append('\n');
        }
        builder.append('\n');
    }

    /**
 * 规范化{@code Selected}文件。
 *
 * @param selectedFiles 待处理的 {@code selectedFiles} 集合
 * @return {@code Selected}文件集合
 */
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

    private String compressProjectContext(String context) {
        return contextCompressionService.compressProjectContext(context);
    }

    /** 返回{@code infer}{@code Intent}。 */
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
            int indexedSymbolCount,
            List<Map<String, Object>> indexHits,
            String contextMode,
            String projectContext,
            List<ProjectFileContext> projectFiles
    ) {
        public ProjectContextPackage {
            selectedFiles = selectedFiles == null ? List.of() : List.copyOf(selectedFiles);
            indexHits = indexHits == null ? List.of() : List.copyOf(indexHits);
            projectContext = projectContext == null ? "" : projectContext;
            projectFiles = projectFiles == null ? List.of() : List.copyOf(projectFiles);
        }

    }

    public record ProjectIndexRecall(
            WorkspaceSemanticIndex indexSnapshot,
            List<Map<String, Object>> indexHits
    ) {
        public ProjectIndexRecall {
            indexHits = indexHits == null ? List.of() : List.copyOf(indexHits);
        }
    }

    /** 返回{@code contains}{@code Any}。 */
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
