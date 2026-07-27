package com.rush.rushaicodemother.orchestration.context;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.GenerationProjectContextProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceFileMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 通过受控工作区边界读取语义索引选中的模型上下文文件。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeneratedProjectContextService {

    private static final Set<String> INDEXABLE_SOURCE_EXTENSIONS = Set.of(
            "vue", "js", "ts", "jsx", "tsx", "css", "scss", "less", "json", "svg", "md",
            "html", "java", "xml", "go", "sql", "mod", "sum", "yml", "yaml"
    );
    private static final Set<String> INDEXABLE_ROOT_FILES = Set.of(
            "package.json", "vite.config.js", "vite.config.ts", "index.html", "tsconfig.json",
            "tsconfig.app.json", "go.mod", "go.sum", "README.md", "docker-compose.yml"
    );
    private static final List<String> INDEXABLE_SOURCE_PREFIXES = List.of(
            "src/", "public/", "cmd/", "internal/", "sql/", "frontend/", "backend/"
    );
    private static final String TRUNCATION_MARKER = "\n[文件内容已按读取预算截断]";
    private static final String TOTAL_TRUNCATION_MARKER = "\n[项目上下文已按总预算截断]";

    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final GenerationProjectContextProperties properties;

    public List<ProjectFileContext> readSelectedFiles(Path rootDirectory, List<String> relativePaths) {
        if (rootDirectory == null || relativePaths == null || relativePaths.isEmpty()) {
            return List.of();
        }
        int remainingChars = properties.getMaxTotalContextChars();
        List<ProjectFileContext> contexts = new ArrayList<>();
        for (String relativePath : normalize(relativePaths)) {
            if (remainingChars <= 0 || !isIndexableProjectFile(relativePath)) {
                continue;
            }
            try {
                WorkspaceFileMetadata file = workspaceFileSystemService.resolveExistingFile(
                        rootDirectory, relativePath);
                String content = workspaceFileSystemService.readUtf8(
                        rootDirectory, file, properties.getMaxReadableFileBytes());
                int fileBudget = Math.min(properties.getMaxSingleFileChars(), remainingChars);
                BoundedContent boundedContent = truncate(content, fileBudget);
                contexts.add(new ProjectFileContext(
                        relativePath,
                        extensionOf(relativePath),
                        boundedContent.content(),
                        boundedContent.truncated()
                ));
                remainingChars -= boundedContent.content().length();
            } catch (Exception failure) {
                log.debug(
                        "跳过不可安全读取的项目上下文文件，path: {}，exceptionType: {}",
                        relativePath,
                        failure.getClass().getSimpleName()
                );
            }
        }
        return List.copyOf(contexts);
    }

    public String boundAssembledContext(String context) {
        String safeContext = context == null ? "" : context;
        return truncate(safeContext, properties.getMaxTotalContextChars(), TOTAL_TRUNCATION_MARKER)
                .content();
    }

    public String buildSelectedFileSections(Path rootDirectory,
                                            List<String> relativePaths,
                                            int usedContextChars) {
        int remainingChars = Math.max(
                0, properties.getMaxTotalContextChars() - Math.max(0, usedContextChars));
        if (remainingChars == 0) {
            return "";
        }
        StringBuilder sections = new StringBuilder();
        for (ProjectFileContext fileContext : readSelectedFiles(rootDirectory, relativePaths)) {
            String separator = sections.isEmpty() ? "" : "\n\n";
            String fence = selectFence(fileContext.content());
            String prefix = "当前文件: " + fileContext.relativePath()
                    + "\n" + fence + fileContext.extension() + "\n";
            String suffix = "\n" + fence;
            int contentBudget = remainingChars
                    - sections.length()
                    - separator.length()
                    - prefix.length()
                    - suffix.length();
            if (contentBudget <= 0) {
                break;
            }
            String boundedContent = truncate(
                    fileContext.content(), contentBudget, TRUNCATION_MARKER).content();
            sections.append(separator)
                    .append(prefix)
                    .append(boundedContent)
                    .append(suffix);
        }
        return sections.toString();
    }

    private List<String> normalize(List<String> relativePaths) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String relativePath : relativePaths) {
            if (StrUtil.isBlank(relativePath)) {
                continue;
            }
            String candidate = relativePath.trim().replace('\\', '/');
            if (candidate.startsWith("/") || candidate.contains("../")
                    || candidate.equals("..") || candidate.contains("/..")) {
                continue;
            }
            normalized.add(candidate);
            if (normalized.size() >= properties.getMaxProjectIndexFiles()) {
                break;
            }
        }
        return List.copyOf(normalized);
    }

    private boolean isIndexableProjectFile(String relativePath) {
        if (INDEXABLE_ROOT_FILES.contains(relativePath)) {
            return true;
        }
        return INDEXABLE_SOURCE_PREFIXES.stream().anyMatch(relativePath::startsWith)
                && INDEXABLE_SOURCE_EXTENSIONS.contains(extensionOf(relativePath));
    }

    private String extensionOf(String relativePath) {
        int slashIndex = relativePath.lastIndexOf('/');
        int dotIndex = relativePath.lastIndexOf('.');
        if (dotIndex <= slashIndex || dotIndex == relativePath.length() - 1) {
            return "text";
        }
        return relativePath.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String selectFence(String content) {
        int backtickLength = Math.max(3, longestRun(content, (char) 96) + 1);
        int tildeLength = Math.max(3, longestRun(content, '~') + 1);
        char delimiter = backtickLength <= tildeLength ? (char) 96 : '~';
        return String.valueOf(delimiter).repeat(Math.min(backtickLength, tildeLength));
    }

    private int longestRun(String content, char target) {
        int longest = 0;
        int current = 0;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == target) {
                current++;
                longest = Math.max(longest, current);
            } else {
                current = 0;
            }
        }
        return longest;
    }

    private BoundedContent truncate(String content, int maxChars) {
        return truncate(content, maxChars, TRUNCATION_MARKER);
    }

    private BoundedContent truncate(String content, int maxChars, String marker) {
        String safeContent = content == null ? "" : content;
        if (safeContent.length() <= maxChars) {
            return new BoundedContent(safeContent, false);
        }
        if (maxChars <= marker.length()) {
            return new BoundedContent(safeContent.substring(0, maxChars), true);
        }
        return new BoundedContent(
                safeContent.substring(0, maxChars - marker.length()) + marker,
                true
        );
    }

    public record ProjectFileContext(
            String relativePath,
            String extension,
            String content,
            boolean truncated
    ) {
    }

    private record BoundedContent(String content, boolean truncated) {
    }
}
