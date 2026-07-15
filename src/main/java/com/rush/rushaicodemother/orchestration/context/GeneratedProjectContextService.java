package com.rush.rushaicodemother.orchestration.context;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.GenerationProjectContextProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceFileMetadata;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceScan;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a bounded, model-facing view of an existing generated project.
 *
 * <p>This module owns project scanning, key-file selection, stable bounded reads, prompt formatting,
 * and the total context budget. Callers only decide when the context should be evaluated.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeneratedProjectContextService {

    private static final Set<String> INDEXABLE_SOURCE_EXTENSIONS = Set.of(
            "vue", "js", "ts", "jsx", "tsx", "css", "scss", "less", "json", "svg", "md",
            "go", "sql", "mod", "sum", "yml", "yaml"
    );
    private static final Set<String> INDEXABLE_ROOT_FILES = Set.of(
            "package.json", "vite.config.js", "vite.config.ts", "index.html", "tsconfig.json",
            "tsconfig.app.json", "go.mod", "go.sum", "README.md", "docker-compose.yml"
    );
    private static final List<String> INDEXABLE_SOURCE_PREFIXES = List.of(
            "src/", "public/", "cmd/", "internal/", "sql/", "frontend/", "backend/"
    );
    private static final String TRUNCATION_MARKER = "\n[文件内容已按上下文预算截断]";

    private final GenerationWorkspaceService generationWorkspaceService;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final GenerationProjectContextProperties properties;

    /** Returns an empty context when no safe, existing generated workspace can be read. */
    public String build(App app) {
        Long appId = app == null ? null : app.getId();
        CodeGenTypeEnum codeGenType = app == null
                ? null
                : CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (appId == null || appId <= 0 || codeGenType == null) {
            return "";
        }

        try {
            GenerationWorkspace workspace = generationWorkspaceService.resolve(appId, codeGenType);
            if (!workspace.exists()) {
                return "";
            }
            WorkspaceScan scan = workspaceFileSystemService.scanProject(workspace.canonicalRootPath());
            return assembleContext(scan, codeGenType);
        } catch (Exception exception) {
            log.warn(
                    "构建项目上下文失败，appId: {}, error: {}",
                    appId,
                    LogExceptionSanitizer.sanitizeMessage(exception)
            );
            return "";
        }
    }

    private String assembleContext(WorkspaceScan scan, CodeGenTypeEnum codeGenType) {
        ContextAccumulator accumulator = new ContextAccumulator(properties.getMaxTotalContextChars());
        accumulator.append(buildProjectIndex(scan));

        Map<String, WorkspaceFileMetadata> filesByRelativePath = new LinkedHashMap<>();
        for (WorkspaceFileMetadata file : scan.files()) {
            filesByRelativePath.putIfAbsent(file.relativePath(), file);
        }
        for (String relativePath : keyFiles(codeGenType)) {
            WorkspaceFileMetadata file = filesByRelativePath.get(relativePath);
            if (file == null || accumulator.remainingSectionChars() <= 0) {
                continue;
            }
            appendFileContext(accumulator, scan, file);
        }
        return accumulator.content();
    }

    private String buildProjectIndex(WorkspaceScan scan) {
        List<String> indexedPaths = scan.files().stream()
                .map(WorkspaceFileMetadata::relativePath)
                .filter(this::isIndexableProjectFile)
                .limit(properties.getMaxProjectIndexFiles())
                .toList();
        if (indexedPaths.isEmpty()) {
            return "";
        }
        StringBuilder index = new StringBuilder("项目索引:\n");
        indexedPaths.forEach(relativePath -> index.append("- ").append(relativePath).append('\n'));
        return index.toString().stripTrailing();
    }

    private boolean isIndexableProjectFile(String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return false;
        }
        if (INDEXABLE_ROOT_FILES.contains(relativePath)) {
            return true;
        }
        boolean isSourcePath = INDEXABLE_SOURCE_PREFIXES.stream().anyMatch(relativePath::startsWith);
        return isSourcePath && INDEXABLE_SOURCE_EXTENSIONS.contains(extensionOf(relativePath));
    }

    private void appendFileContext(ContextAccumulator accumulator,
                                   WorkspaceScan scan,
                                   WorkspaceFileMetadata file) {
        try {
            String content = workspaceFileSystemService.readUtf8(
                    scan,
                    file,
                    properties.getMaxReadableFileBytes()
            );
            String boundedContent = truncate(content, properties.getMaxSingleFileChars());
            String section = formatFileSection(
                    file.relativePath(),
                    boundedContent,
                    accumulator.remainingSectionChars()
            );
            accumulator.append(section);
        } catch (Exception exception) {
            log.debug(
                    "跳过不可读取的项目上下文文件，path: {}, exceptionType: {}",
                    file.relativePath(),
                    exception.getClass().getSimpleName()
            );
        }
    }

    private String formatFileSection(String relativePath, String content, int maxSectionChars) {
        if (maxSectionChars <= 0) {
            return "";
        }
        String fence = selectFence(content);
        String prefix = "当前文件: " + relativePath + "\n" + fence + extensionOf(relativePath) + "\n";
        String suffix = "\n" + fence;
        int maxContentChars = maxSectionChars - prefix.length() - suffix.length();
        if (maxContentChars <= 0) {
            return "";
        }
        return prefix + truncate(content, maxContentChars) + suffix;
    }

    private String selectFence(String content) {
        int backtickLength = Math.max(3, longestRun(content, (char) 96) + 1);
        int tildeLength = Math.max(3, longestRun(content, '~') + 1);
        char delimiter = backtickLength <= tildeLength ? (char) 96 : '~';
        int length = Math.min(backtickLength, tildeLength);
        return String.valueOf(delimiter).repeat(length);
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

    private String truncate(String content, int maxChars) {
        if (content == null || maxChars <= 0) {
            return "";
        }
        if (content.length() <= maxChars) {
            return content;
        }
        if (maxChars <= TRUNCATION_MARKER.length()) {
            return content.substring(0, maxChars);
        }
        return content.substring(0, maxChars - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
    }

    private String extensionOf(String relativePath) {
        int slashIndex = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        int dotIndex = relativePath.lastIndexOf('.');
        if (dotIndex <= slashIndex || dotIndex == relativePath.length() - 1) {
            return "text";
        }
        return relativePath.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private List<String> keyFiles(CodeGenTypeEnum codeGenType) {
        return switch (codeGenType) {
            case HTML -> List.of("index.html");
            case MULTI_FILE -> List.of("index.html", "style.css", "script.js");
            case VUE_PROJECT -> List.of("src/App.vue", "src/main.js", "src/main.ts", "index.html");
            case BACKEND_PROJECT -> List.of(
                    "go.mod", "cmd/server/main.go", "internal/config/config.go",
                    "internal/database/database.go", "sql/schema.sql"
            );
            case FULL_STACK_PROJECT -> List.of(
                    "frontend/package.json", "frontend/src/services/request.ts", "frontend/src/App.vue",
                    "backend/go.mod", "backend/cmd/server/main.go", "backend/internal/config/config.go",
                    "backend/sql/schema.sql"
            );
        };
    }

    private static final class ContextAccumulator {

        private final int maxChars;
        private final StringBuilder builder = new StringBuilder();

        private ContextAccumulator(int maxChars) {
            this.maxChars = maxChars;
        }

        private void append(String section) {
            if (StrUtil.isBlank(section) || builder.length() >= maxChars) {
                return;
            }
            int separatorLength = builder.isEmpty() ? 0 : 2;
            int remaining = maxChars - builder.length() - separatorLength;
            if (remaining <= 0) {
                return;
            }
            if (separatorLength > 0) {
                builder.append("\n\n");
            }
            builder.append(section, 0, Math.min(section.length(), remaining));
        }

        private int remainingSectionChars() {
            int separatorLength = builder.isEmpty() ? 0 : 2;
            return Math.max(0, maxChars - builder.length() - separatorLength);
        }

        private String content() {
            return builder.toString();
        }
    }
}
