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

    private static final int MAX_CANDIDATE_FILES = 8;
    private static final int MAX_SINGLE_FILE_CHARS = 20 * 1024;
    private static final int MAX_TOTAL_CONTEXT_CHARS = 60 * 1024;

    /**
     * 文件路径模式
     */
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "((?:src/|public/|components/|views/|pages/|assets/|styles/|utils/|api/|router/|store/)\\S+\\.(?:vue|js|ts|jsx|tsx|css|scss|less|html|json))"
    );

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

        // 2. 语义索引搜索
        List<EditFileCandidate> semanticMatches = searchBySemanticIndex(workspace, userMessage);
        for (EditFileCandidate candidate : semanticMatches) {
            if (seenPaths.add(candidate.relativePath())) {
                candidates.add(candidate);
            }
        }

        // 3. 按 code type 加固定入口文件兜底
        List<EditFileCandidate> fallbackMatches = getFallbackFiles(workspace, codeGenType);
        for (EditFileCandidate candidate : fallbackMatches) {
            if (seenPaths.add(candidate.relativePath())) {
                candidates.add(candidate);
            }
        }

        // 限制候选文件数量
        if (candidates.size() > MAX_CANDIDATE_FILES) {
            candidates = candidates.subList(0, MAX_CANDIDATE_FILES);
        }

        return candidates;
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

    private boolean isInHiddenDirectory(Path filePath, GenerationWorkspace workspace) {
        Path relative = workspace.canonicalRootPath().relativize(filePath);
        for (Path part : relative) {
            if (GenerationWorkspaceService.HIDDEN_FILE_NAMES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isIndexableFile(String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return false;
        }
        String ext = FileUtil.extName(relativePath).toLowerCase();
        return Set.of("vue", "js", "ts", "jsx", "tsx", "css", "scss", "less",
                "html", "json", "go", "sql", "md", "yml", "yaml").contains(ext);
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
