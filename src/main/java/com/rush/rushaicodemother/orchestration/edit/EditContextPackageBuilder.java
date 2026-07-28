package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.config.EditLocatorProperties;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 为编辑请求构建有界的、路径安全的源上下文包。 */
@Service
@RequiredArgsConstructor
public class EditContextPackageBuilder {

    private static final String FILE_TRUNCATION_MARKER = "\n// ... \u6587\u4ef6\u5185\u5bb9\u8fc7\u957f\uff0c\u5df2\u622a\u65ad ...";
    private static final String TOTAL_TRUNCATION_MARKER = "\n// ... \u5df2\u622a\u65ad ...";

    private final EditWorkspaceFileService workspaceFileService;
    private final EditLocatorProperties properties;

    /**
 * 构建并返回编辑上下文依赖包{@code Builder}。
 *
 * @param workspace 工作区
 * @param candidates 待处理的 {@code candidates} 集合
 * @return 编辑上下文依赖包{@code Builder}
 */
    public EditContextPackage build(GenerationWorkspace workspace, List<EditFileCandidate> candidates) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (workspace == null || candidates == null || candidates.isEmpty()) {
            return emptyPackage();
        }

        List<EditFileCandidate> acceptedCandidates = new ArrayList<>();
        Map<String, String> fileContents = new LinkedHashMap<>();
        int totalChars = 0;
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (EditFileCandidate candidate : candidates) {
            if (totalChars >= properties.getMaxTotalContextChars()) {
                break;
            }
            if (candidate == null) {
                continue;
            }
            var safeFile = workspaceFileService.resolveEditableFile(workspace, candidate.relativePath());
            if (safeFile.isEmpty() || fileContents.containsKey(safeFile.get().relativePath())) {
                continue;
            }
            String content = workspaceFileService.readUtf8(workspace, safeFile.get()).orElse(null);
            if (content == null) {
                continue;
            }

            content = truncate(content, properties.getMaxSingleFileChars(), FILE_TRUNCATION_MARKER);
            int remainingChars = properties.getMaxTotalContextChars() - totalChars;
            content = truncate(content, remainingChars, TOTAL_TRUNCATION_MARKER);
            if (content.isEmpty() && remainingChars <= 0) {
                break;
            }

            EditFileCandidate normalizedCandidate = normalizeCandidate(candidate, safeFile.get());
            acceptedCandidates.add(normalizedCandidate);
            fileContents.put(safeFile.get().relativePath(), content);
            totalChars += content.length();
        }

        return new EditContextPackage(
                List.copyOf(acceptedCandidates),
                Collections.unmodifiableMap(new LinkedHashMap<>(fileContents)),
                totalChars,
                buildProjectIndex(workspace)
        );
    }

    private EditFileCandidate normalizeCandidate(EditFileCandidate candidate, EditWorkspaceFile file) {
        return new EditFileCandidate(
                file.relativePath(),
                file.fileName(),
                candidate.matchType(),
                candidate.score(),
                candidate.reason(),
                candidate.matchedTerms() == null ? List.of() : List.copyOf(candidate.matchedTerms())
        );
    }

    /** 构建并返回项目索引。 */
    private String buildProjectIndex(GenerationWorkspace workspace) {
        List<String> indexedFiles = workspaceFileService.scanIndexableFiles(workspace, "").stream()
                .map(EditWorkspaceFile::relativePath)
                .limit(properties.getMaxProjectIndexFiles())
                .toList();
        if (indexedFiles.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("\u9879\u76ee\u6587\u4ef6\u7d22\u5f15:\n");
        indexedFiles.forEach(path -> builder.append("- ").append(path).append('\n'));
        return builder.toString().trim();
    }

    /** 按资源上限截断编辑上下文依赖包{@code Builder}。 */
    private String truncate(String content, int maxChars, String marker) {
        if (content == null || maxChars <= 0) {
            return "";
        }
        if (content.length() <= maxChars) {
            return content;
        }
        if (marker.length() >= maxChars) {
            return marker.substring(0, maxChars);
        }
        return content.substring(0, maxChars - marker.length()) + marker;
    }

    private EditContextPackage emptyPackage() {
        return new EditContextPackage(List.of(), Map.of(), 0, "");
    }
}
