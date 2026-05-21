package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量编辑文件定位服务。
 * 基于语义索引和用户消息定位需要编辑的文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EditFileLocatorService {

    private final WorkspaceSemanticIndexService workspaceSemanticIndexService;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final EditStatePersistenceService editStatePersistenceService;

    private static final int MAX_CANDIDATE_FILES = 8;
    private static final int MAX_SINGLE_FILE_CHARS = 20 * 1024;
    private static final int MAX_TOTAL_CONTEXT_CHARS = 60 * 1024;

    /**
     * 文件路径模式
     */
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "((?:src/|public/|components/|views/|pages/|assets/|styles/|utils/|api/|router/|store/)\\S+\\.(?:vue|js|ts|jsx|tsx|css|scss|less|html|json))"
    );
    private static final Pattern ROUTE_COMPONENT_NOT_FOUND_PATTERN = Pattern.compile(
            "Route component not found in src/views:\\s*([A-Za-z0-9_.$-]+)"
    );
    private static final Pattern VITE_IMPORT_ANALYSIS_PATTERN = Pattern.compile(
            "Failed to resolve import\\s+\"([^\"]+)\"\\s+from\\s+\"([^\"]+)\""
    );
    private static final Pattern SELECTED_ELEMENT_CLASS_PATTERN = Pattern.compile("\\.([A-Za-z_][\\w-]*)");
    private static final Pattern SELECTED_ELEMENT_CONTENT_PATTERN = Pattern.compile("- 当前内容:\\s*(.+)");

    /**
     * 定位需要编辑的文件。
     *
     * @param workspace    工作区
     * @param userMessage  用户消息
     * @param codeGenType  代码生成类型
     * @return 候选文件列表
     */
    public List<EditFileCandidate> locate(GenerationWorkspace workspace, String userMessage, CodeGenTypeEnum codeGenType) {
        if (workspace == null || !workspace.exists()) {
            return List.of();
        }

        Set<String> seenPaths = new LinkedHashSet<>();
        List<EditFileCandidate> candidates = new ArrayList<>();

        // 1. 用户消息中包含明确路径 → 最高优先级
        List<EditFileCandidate> pathMatches = extractPathCandidates(workspace, userMessage);
        for (EditFileCandidate candidate : pathMatches) {
            if (seenPaths.add(candidate.relativePath())) {
                candidates.add(candidate);
            }
        }

        // 2. 基于可视化编辑选中元素补充确定性上下文
        List<EditFileCandidate> selectedElementMatches = getSelectedElementContextFiles(workspace, userMessage);
        for (EditFileCandidate candidate : selectedElementMatches) {
            if (seenPaths.add(candidate.relativePath())) {
                candidates.add(candidate);
            }
        }

        // 3. 对常见运行时错误补充确定性上下文，避免 AI 只凭路由文件猜测真实组件名
        List<EditFileCandidate> diagnosticMatches = getDiagnosticContextFiles(workspace, userMessage);
        for (EditFileCandidate candidate : diagnosticMatches) {
            if (seenPaths.add(candidate.relativePath())) {
                candidates.add(candidate);
            }
        }

        // 4. 最近修改的文件提权（连续改修时优先命中刚改过的文件）
        List<EditFileCandidate> recentMatches = getRecentModifiedFiles(workspace, userMessage);
        for (EditFileCandidate candidate : recentMatches) {
            if (seenPaths.add(candidate.relativePath())) {
                candidates.add(candidate);
            }
        }

        // 5. 语义索引搜索
        List<EditFileCandidate> semanticMatches = searchBySemanticIndex(workspace, userMessage);
        for (EditFileCandidate candidate : semanticMatches) {
            if (seenPaths.add(candidate.relativePath())) {
                candidates.add(candidate);
            }
        }

        // 6. 按 code type 加固定入口文件兜底
        List<EditFileCandidate> fallbackMatches = getFallbackFiles(workspace, codeGenType);
        for (EditFileCandidate candidate : fallbackMatches) {
            if (seenPaths.add(candidate.relativePath())) {
                candidates.add(candidate);
            }
        }

        candidates = candidates.stream()
                .filter(candidate -> candidate != null && isEditableCandidatePath(candidate.relativePath()))
                .limit(MAX_CANDIDATE_FILES)
                .toList();

        return candidates;
    }

    /**
     * 可视化编辑会把 DOM 选择器和当前文本拼进用户消息。这里用这些强信号直接扫描源码，
     * 避免仅靠“页面、按钮”这类弱词把上下文定位到无关入口文件或 3D 示例组件。
     */
    private List<EditFileCandidate> getSelectedElementContextFiles(GenerationWorkspace workspace, String userMessage) {
        if (workspace == null || StrUtil.isBlank(userMessage) || !userMessage.contains("选中元素信息")) {
            return List.of();
        }

        LinkedHashSet<String> classNames = extractSelectedElementClassNames(userMessage);
        LinkedHashSet<String> textSignals = extractSelectedElementTextSignals(userMessage);
        if (classNames.isEmpty() && textSignals.isEmpty()) {
            return List.of();
        }

        List<EditFileCandidate> candidates = new ArrayList<>();
        Path root = workspace.canonicalRootPath();
        try {
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(path -> isIndexableFile(root.relativize(path).toString()))
                    .map(path -> scoreSelectedElementFile(workspace, path, classNames, textSignals))
                    .filter(candidate -> candidate != null && candidate.score() > 0)
                    .sorted(Comparator.comparingInt(EditFileCandidate::score).reversed()
                            .thenComparing(EditFileCandidate::relativePath))
                    .limit(MAX_CANDIDATE_FILES)
                    .forEach(candidates::add);
        } catch (Exception e) {
            log.debug("基于选中元素定位文件失败: {}", e.getMessage());
        }
        return candidates;
    }

    private LinkedHashSet<String> extractSelectedElementClassNames(String userMessage) {
        LinkedHashSet<String> classNames = new LinkedHashSet<>();
        String selector = extractSelectedElementSelector(userMessage);
        if (StrUtil.isBlank(selector)) {
            return classNames;
        }
        Matcher matcher = SELECTED_ELEMENT_CLASS_PATTERN.matcher(selector);
        while (matcher.find() && classNames.size() < 20) {
            String className = matcher.group(1);
            if (StrUtil.isNotBlank(className) && !className.startsWith("nth-child")) {
                classNames.add(className);
            }
        }
        return classNames;
    }

    private String extractSelectedElementSelector(String userMessage) {
        if (StrUtil.isBlank(userMessage)) {
            return "";
        }
        for (String line : userMessage.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- 选择器:")) {
                return trimmed.substring("- 选择器:".length()).trim();
            }
        }
        return "";
    }

    private LinkedHashSet<String> extractSelectedElementTextSignals(String userMessage) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        Matcher matcher = SELECTED_ELEMENT_CONTENT_PATTERN.matcher(userMessage);
        if (!matcher.find()) {
            return signals;
        }
        String content = matcher.group(1).trim();
        if (StrUtil.isBlank(content)) {
            return signals;
        }
        if (content.contains("加入购物车")) {
            signals.add("加入购物车");
        }
        Matcher chineseMatcher = Pattern.compile("[\\p{IsHan}]{2,}").matcher(content);
        while (chineseMatcher.find() && signals.size() < 8) {
            signals.add(chineseMatcher.group());
        }
        return signals;
    }

    private EditFileCandidate scoreSelectedElementFile(GenerationWorkspace workspace,
                                                       Path path,
                                                       Set<String> classNames,
                                                       Set<String> textSignals) {
        String relativePath = workspace.canonicalRootPath().relativize(path).toString().replace('\\', '/');
        String fileName = extractFileName(relativePath);
        String content;
        try {
            content = FileUtil.readString(path.toFile(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }

        int score = 0;
        List<String> matchedTerms = new ArrayList<>();
        for (String className : classNames) {
            String componentName = kebabToPascal(className) + ".vue";
            if (fileName.equalsIgnoreCase(componentName)) {
                score += 180;
                matchedTerms.add(className);
            }
            if (content.contains(className)) {
                score += className.contains("-") ? 70 : 35;
                matchedTerms.add(className);
            }
        }
        for (String signal : textSignals) {
            if (content.contains(signal)) {
                score += "加入购物车".equals(signal) ? 150 : 60;
                matchedTerms.add(signal);
            }
        }
        if (relativePath.endsWith(".css") && classNames.stream().anyMatch(content::contains)) {
            score += 40;
        }
        if (score <= 0) {
            return null;
        }
        return new EditFileCandidate(
                relativePath,
                fileName,
                "selected_element",
                score,
                "选中元素的 DOM 类名或文本命中源码",
                matchedTerms.stream().distinct().toList()
        );
    }

    private String kebabToPascal(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String part : value.split("-")) {
            if (StrUtil.isBlank(part)) {
                continue;
            }
            builder.append(part.substring(0, 1).toUpperCase()).append(part.substring(1));
        }
        return builder.toString();
    }

    /**
     * 构建编辑上下文包，包含候选文件的内容。
     *
     * @param workspace   工作区
     * @param candidates  候选文件列表
     * @return 上下文包
     */
    public EditContextPackage buildContextPackage(GenerationWorkspace workspace, List<EditFileCandidate> candidates) {
        if (workspace == null || candidates == null || candidates.isEmpty()) {
            return new EditContextPackage(List.of(), Map.of(), 0, "");
        }

        Map<String, String> fileContents = new LinkedHashMap<>();
        int totalChars = 0;

        for (EditFileCandidate candidate : candidates) {
            if (totalChars >= MAX_TOTAL_CONTEXT_CHARS) {
                break;
            }

            Path filePath = workspace.canonicalRootPath().resolve(candidate.relativePath());
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                continue;
            }

            // 检查是否在隐藏目录中
            if (isInHiddenDirectory(filePath, workspace)) {
                continue;
            }

            try {
                String content = FileUtil.readString(filePath.toFile(), StandardCharsets.UTF_8);
                if (content.length() > MAX_SINGLE_FILE_CHARS) {
                    content = content.substring(0, MAX_SINGLE_FILE_CHARS)
                            + "\n// ... 文件内容过长，已截断 ...";
                }
                if (totalChars + content.length() > MAX_TOTAL_CONTEXT_CHARS) {
                    int remaining = MAX_TOTAL_CONTEXT_CHARS - totalChars;
                    if (remaining > 200) {
                        content = content.substring(0, remaining) + "\n// ... 已截断 ...";
                    } else {
                        break;
                    }
                }
                fileContents.put(candidate.relativePath(), content);
                totalChars += content.length();
            } catch (Exception e) {
                log.debug("读取文件内容失败，跳过: {}", candidate.relativePath(), e);
            }
        }

        // 构建简要项目索引
        String projectIndex = buildProjectIndex(workspace);

        return new EditContextPackage(
                candidates.stream()
                        .filter(c -> fileContents.containsKey(c.relativePath()))
                        .toList(),
                fileContents,
                totalChars,
                projectIndex
        );
    }

    /**
     * 从用户消息中提取明确的文件路径。
     */
    private List<EditFileCandidate> extractPathCandidates(GenerationWorkspace workspace, String userMessage) {
        List<EditFileCandidate> candidates = new ArrayList<>();
        if (StrUtil.isBlank(userMessage)) {
            return candidates;
        }

        Matcher matcher = PATH_PATTERN.matcher(userMessage);
        while (matcher.find() && candidates.size() < MAX_CANDIDATE_FILES) {
            String path = matcher.group(1);
            Path filePath = workspace.canonicalRootPath().resolve(path);
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                candidates.add(new EditFileCandidate(
                        path,
                        filePath.getFileName().toString(),
                        "explicit_path",
                        200,
                        "用户消息中明确提到的文件路径",
                        List.of(path)
                ));
            }
        }

        return candidates;
    }

    /**
     * 使用语义索引搜索相关文件。
     */
    private List<EditFileCandidate> searchBySemanticIndex(GenerationWorkspace workspace, String userMessage) {
        List<EditFileCandidate> candidates = new ArrayList<>();
        if (StrUtil.isBlank(userMessage)) {
            return candidates;
        }

        try {
            List<String> suggestedFiles = workspaceSemanticIndexService.suggestFiles(
                    workspace.canonicalRootPath(),
                    userMessage,
                    MAX_CANDIDATE_FILES
            );
            for (String relativePath : suggestedFiles) {
                candidates.add(new EditFileCandidate(
                        relativePath,
                        extractFileName(relativePath),
                        "semantic_search",
                        100,
                        "语义索引匹配",
                        List.of()
                ));
            }
        } catch (Exception e) {
            log.warn("语义索引搜索失败: {}", e.getMessage());
        }

        return candidates;
    }

    /**
     * 获取最近修改的文件（连续改修时优先命中刚改过的文件）。
     */
    private List<EditFileCandidate> getRecentModifiedFiles(GenerationWorkspace workspace, String userMessage) {
        List<EditFileCandidate> candidates = new ArrayList<>();
        if (StrUtil.isBlank(userMessage)) {
            return candidates;
        }

        try {
            // 获取与用户消息相关的最近修改文件
            List<String> relevantFiles = editStatePersistenceService.getRelevantRecentFiles(
                    workspace.appId(), userMessage, 3
            );
            for (String relativePath : relevantFiles) {
                Path filePath = workspace.canonicalRootPath().resolve(relativePath);
                if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                    candidates.add(new EditFileCandidate(
                            relativePath,
                            extractFileName(relativePath),
                            "recent_modified",
                            150,
                            "最近修改的文件",
                            List.of()
                    ));
                }
            }
        } catch (Exception e) {
            log.debug("获取最近修改文件失败: {}", e.getMessage());
        }

        return candidates;
    }

    /**
     * 按代码生成类型返回兜底入口文件。
     */
    private List<EditFileCandidate> getFallbackFiles(GenerationWorkspace workspace, CodeGenTypeEnum codeGenType) {
        List<EditFileCandidate> candidates = new ArrayList<>();
        List<String> fallbackPaths = switch (codeGenType) {
            case HTML -> List.of("index.html");
            case MULTI_FILE -> List.of("index.html", "style.css", "script.js");
            case VUE_PROJECT -> List.of("src/App.vue", "src/main.ts", "src/main.js");
            case BACKEND_PROJECT -> List.of("cmd/server/main.go", "go.mod");
            case FULL_STACK_PROJECT -> List.of("frontend/src/App.vue", "frontend/src/main.ts");
        };

        for (String path : fallbackPaths) {
            Path filePath = workspace.canonicalRootPath().resolve(path);
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                candidates.add(new EditFileCandidate(
                        path,
                        extractFileName(path),
                        "fallback_entry",
                        50,
                        "入口文件兜底",
                        List.of()
                ));
            }
        }

        return candidates;
    }

    /**
     * 针对可从报错文本确定依赖关系的问题，补充路由清单和真实视图文件。
     */
    private List<EditFileCandidate> getDiagnosticContextFiles(GenerationWorkspace workspace, String userMessage) {
        List<EditFileCandidate> candidates = new ArrayList<>();
        if (workspace == null || StrUtil.isBlank(userMessage)) {
            return candidates;
        }

        if (userMessage.contains("Route component not found in src/views")) {
            addIfExists(candidates, workspace, "src/router/routeFactory.js", "route_diagnostic", 200, "路由组件缺失错误需要检查路由解析逻辑");
            addIfExists(candidates, workspace, "src/router/index.js", "route_diagnostic", 175, "路由组件缺失错误需要检查路由入口");
            addIfExists(candidates, workspace, "src/router/routeManifest.json", "route_diagnostic", 180, "路由组件缺失错误需要检查路由清单");
            addVueFiles(candidates, workspace, "src/views", "route_diagnostic", 170, "路由组件缺失错误需要检查真实视图文件");
            addVueFiles(candidates, workspace, "src/pages", "route_diagnostic", 160, "路由组件缺失错误可能涉及 pages 目录");

            Matcher matcher = ROUTE_COMPONENT_NOT_FOUND_PATTERN.matcher(userMessage);
            if (matcher.find()) {
                String componentName = matcher.group(1);
                addMatchingViewFiles(candidates, workspace, componentName);
            }
        }

        if (userMessage.contains("Failed to resolve import")) {
            addIfExists(candidates, workspace, "src/main.js", "import_diagnostic", 195, "Vite import 错误需要检查 JS 入口");
            addIfExists(candidates, workspace, "src/main.ts", "import_diagnostic", 194, "Vite import 错误需要检查 TS 入口");
            addIfExists(candidates, workspace, "src/components/index.ts", "import_diagnostic", 150, "Vite import 错误需要检查组件导出");
            addVueFiles(candidates, workspace, "src/components", "import_diagnostic", 175, "Vite import 错误需要检查组件目录");

            Matcher matcher = VITE_IMPORT_ANALYSIS_PATTERN.matcher(userMessage);
            if (matcher.find()) {
                String importPath = matcher.group(1);
                String fromPath = matcher.group(2);
                addImportErrorContextFiles(candidates, workspace, importPath, fromPath);
            }
        }

        if (userMessage.contains("getActivePinia()")) {
            addIfExists(candidates, workspace, "src/main.js", "pinia_diagnostic", 200, "Pinia 初始化错误需要检查 JS 入口");
            addIfExists(candidates, workspace, "src/main.ts", "pinia_diagnostic", 199, "Pinia 初始化错误需要检查 TS 入口");
            addIfExists(candidates, workspace, "src/stores/index.ts", "pinia_diagnostic", 180, "Pinia 初始化错误需要检查 store 入口");
            addFiles(candidates, workspace, "src/stores", "pinia_diagnostic", 175, "Pinia 初始化错误需要检查 store 定义", 20);
        }

        return candidates;
    }

    private void addImportErrorContextFiles(List<EditFileCandidate> candidates, GenerationWorkspace workspace, String importPath, String fromPath) {
        if (StrUtil.isNotBlank(fromPath)) {
            String normalizedFromPath = fromPath.replace('\\', '/');
            addIfExists(candidates, workspace, normalizedFromPath, "import_source_match", 210, "Vite import 报错直接指向的源文件");
        }
        if (StrUtil.isBlank(importPath)) {
            return;
        }
        String normalizedImportPath = importPath.replace('\\', '/');
        if (normalizedImportPath.startsWith("@/")) {
            String aliasPath = "src/" + normalizedImportPath.substring(2);
            addIfExists(candidates, workspace, aliasPath, "import_target_match", 205, "Vite import 报错直接指向的目标文件");
        }
        String fileName = extractFileName(normalizedImportPath);
        if (StrUtil.isNotBlank(fileName)) {
            addMatchingByFileName(candidates, workspace, fileName);
        }
    }

    private void addMatchingByFileName(List<EditFileCandidate> candidates, GenerationWorkspace workspace, String fileName) {
        Path root = workspace.canonicalRootPath();
        try {
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(path -> isEditableCandidatePath(workspace.canonicalRootPath().relativize(path).toString()))
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(fileName))
                    .limit(12)
                    .forEach(path -> addPath(candidates, workspace, path, "import_target_guess", 170, "与缺失 import 文件名相同的候选文件"));
        } catch (Exception e) {
            log.debug("按文件名匹配 import 目标失败: {}", fileName, e);
        }
    }

    private void addMatchingViewFiles(List<EditFileCandidate> candidates, GenerationWorkspace workspace, String componentName) {
        if (StrUtil.isBlank(componentName)) {
            return;
        }
        String normalized = normalizeComponentName(componentName);
        addMatchingVueFiles(candidates, workspace, "src/views", normalized, "route_component_match", 190, "与报错组件名相近的视图文件");
        addMatchingVueFiles(candidates, workspace, "src/pages", normalized, "route_component_match", 185, "与报错组件名相近的页面文件");
    }

    private void addMatchingVueFiles(List<EditFileCandidate> candidates, GenerationWorkspace workspace, String dir, String componentName,
                                     String matchType, int score, String reason) {
        Path root = workspace.canonicalRootPath().resolve(dir);
        if (!Files.isDirectory(root)) {
            return;
        }
        try {
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(path -> isEditableCandidatePath(workspace.canonicalRootPath().relativize(path).toString()))
                    .filter(path -> path.getFileName().toString().endsWith(".vue"))
                    .filter(path -> componentNameMatches(componentName, path))
                    .limit(12)
                    .forEach(path -> addPath(candidates, workspace, path, matchType, score, reason));
        } catch (Exception e) {
            log.debug("读取匹配视图文件失败: {}", dir, e);
        }
    }

    private boolean componentNameMatches(String componentName, Path path) {
        String fileName = FileUtil.mainName(path.getFileName().toString());
        String parentName = path.getParent() == null ? "" : path.getParent().getFileName().toString();
        return normalizeComponentName(fileName).equals(componentName)
                || normalizeComponentName(parentName).equals(componentName);
    }

    private String normalizeComponentName(String name) {
        String normalized = StrUtil.blankToDefault(name, "").trim().toLowerCase();
        if (normalized.endsWith(".vue")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        if (normalized.endsWith("page")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return normalized;
    }

    private void addVueFiles(List<EditFileCandidate> candidates, GenerationWorkspace workspace, String dir,
                             String matchType, int score, String reason) {
        Path root = workspace.canonicalRootPath().resolve(dir);
        if (!Files.isDirectory(root)) {
            return;
        }
        try {
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(path -> isEditableCandidatePath(workspace.canonicalRootPath().relativize(path).toString()))
                    .filter(path -> path.getFileName().toString().endsWith(".vue"))
                    .limit(20)
                    .forEach(path -> addPath(candidates, workspace, path, matchType, score, reason));
        } catch (Exception e) {
            log.debug("读取视图文件列表失败: {}", dir, e);
        }
    }

    private void addFiles(List<EditFileCandidate> candidates, GenerationWorkspace workspace, String dir,
                          String matchType, int score, String reason, int limit) {
        Path root = workspace.canonicalRootPath().resolve(dir);
        if (!Files.isDirectory(root)) {
            return;
        }
        try {
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(path -> isIndexableFile(workspace.canonicalRootPath().relativize(path).toString()))
                    .limit(limit)
                    .forEach(path -> addPath(candidates, workspace, path, matchType, score, reason));
        } catch (Exception e) {
            log.debug("读取目录文件列表失败: {}", dir, e);
        }
    }

    private void addIfExists(List<EditFileCandidate> candidates, GenerationWorkspace workspace, String relativePath,
                             String matchType, int score, String reason) {
        Path path = workspace.canonicalRootPath().resolve(relativePath);
        if (Files.isRegularFile(path)) {
            candidates.add(new EditFileCandidate(
                    relativePath,
                    extractFileName(relativePath),
                    matchType,
                    score,
                    reason,
                    List.of(relativePath)
            ));
        }
    }

    private void addPath(List<EditFileCandidate> candidates, GenerationWorkspace workspace, Path path,
                         String matchType, int score, String reason) {
        try {
            String relativePath = workspace.canonicalRootPath().relativize(path).toString().replace('\\', '/');
            candidates.add(new EditFileCandidate(
                    relativePath,
                    extractFileName(relativePath),
                    matchType,
                    score,
                    reason,
                    List.of(relativePath)
            ));
        } catch (Exception e) {
            log.debug("添加候选文件失败: {}", path, e);
        }
    }

    /**
     * 构建简要项目索引。
     */
    private String buildProjectIndex(GenerationWorkspace workspace) {
        try {
            File rootDir = workspace.canonicalRootPath().toFile();
            if (!rootDir.exists() || !rootDir.isDirectory()) {
                return "";
            }
            List<String> indexedFiles = new ArrayList<>();
            FileUtil.walkFiles(rootDir, file -> {
                if (indexedFiles.size() >= 80) {
                    return;
                }
                if (isHiddenFile(file)) {
                    return;
                }
                String relativePath = normalizeRelativePath(rootDir, file);
                if (isInHiddenPath(relativePath)) {
                    return;
                }
                if (file.isFile() && isIndexableFile(relativePath)) {
                    indexedFiles.add(relativePath);
                }
            });
            if (indexedFiles.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder("项目文件索引:\n");
            indexedFiles.stream().sorted().limit(80).forEach(p -> builder.append("- ").append(p).append('\n'));
            return builder.toString().trim();
        } catch (Exception e) {
            log.debug("构建项目索引失败: {}", e.getMessage());
            return "";
        }
    }

    private boolean isHiddenFile(File file) {
        Set<String> hiddenNames = GenerationWorkspaceService.HIDDEN_FILE_NAMES;
        return hiddenNames.contains(file.getName()) || file.getName().startsWith(".");
    }

    private boolean isInHiddenPath(String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return false;
        }
        for (String part : relativePath.replace('\\', '/').split("/")) {
            if (GenerationWorkspaceService.HIDDEN_FILE_NAMES.contains(part) || part.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private boolean isInHiddenDirectory(Path filePath, GenerationWorkspace workspace) {
        Path relative = workspace.canonicalRootPath().relativize(filePath);
        for (Path part : relative) {
            String name = part.toString();
            if (GenerationWorkspaceService.HIDDEN_FILE_NAMES.contains(name) || name.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private boolean isIndexableFile(String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return false;
        }
        if (!isEditableCandidatePath(relativePath)) {
            return false;
        }
        String ext = FileUtil.extName(relativePath).toLowerCase();
        return Set.of("vue", "js", "ts", "jsx", "tsx", "css", "scss", "less",
                "html", "json", "go", "sql", "md", "yml", "yaml").contains(ext);
    }

    private boolean isEditableCandidatePath(String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return false;
        }
        String normalizedPath = relativePath.replace('\\', '/');
        if (isInHiddenPath(normalizedPath)) {
            return false;
        }
        String fileName = extractFileName(normalizedPath);
        return StrUtil.isNotBlank(fileName) && !fileName.startsWith(".");
    }

    private String normalizeRelativePath(File rootDir, File file) {
        try {
            Path rootPath = rootDir.getCanonicalFile().toPath();
            Path filePath = file.getCanonicalFile().toPath();
            return rootPath.relativize(filePath).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.getName();
        }
    }

    private String extractFileName(String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return "";
        }
        int lastSlash = relativePath.lastIndexOf('/');
        return lastSlash >= 0 ? relativePath.substring(lastSlash + 1) : relativePath;
    }
}
