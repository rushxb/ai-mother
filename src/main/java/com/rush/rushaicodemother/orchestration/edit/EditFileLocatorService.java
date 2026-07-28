package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.EditLocatorProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 订购编辑文件候选源并强制执行单一路径安全结果策略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EditFileLocatorService {

    private static final Pattern PATH_PATTERN = Pattern.compile(
            "((?:src/|public/|components/|views/|pages/|assets/|styles/|utils/|api/|router/|store/)\\S+\\.(?:vue|js|ts|jsx|tsx|css|scss|less|html|json))"
    );
    private static final Pattern BARE_SOURCE_FILE_PATTERN = Pattern.compile(
            "\\b([A-Za-z_$][\\w$.-]*\\.(?:vue|js|ts|jsx|tsx))\\b"
    );

    private final WorkspaceSemanticIndexService semanticIndexService;
    private final EditStatePersistenceService editStatePersistenceService;
    private final SelectedElementFileLocator selectedElementFileLocator;
    private final DiagnosticFileLocator diagnosticFileLocator;
    private final EditWorkspaceFileService workspaceFileService;
    private final EditLocatorProperties properties;

    /**
 * 返回{@code locate}。
 *
 * @param workspace 工作区
 * @param userMessage 用户消息
 * @param codeGenType 代码生成类型
 * @return 编辑文件{@code Locator}集合
 */
    public List<EditFileCandidate> locate(GenerationWorkspace workspace,
                                          String userMessage,
                                          CodeGenTypeEnum codeGenType) {
        if (workspace == null || !workspace.exists()) {
            return List.of();
        }

        List<EditFileCandidate> candidates = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        appendUnique(candidates, seenPaths, workspace, () -> extractPathCandidates(workspace, userMessage));
        appendUnique(candidates, seenPaths, workspace, () -> extractBareFileNameCandidates(workspace, userMessage));
        appendUnique(candidates, seenPaths, workspace, () -> selectedElementFileLocator.locate(workspace, userMessage));
        appendUnique(candidates, seenPaths, workspace, () -> diagnosticFileLocator.locate(workspace, userMessage));
        appendUnique(candidates, seenPaths, workspace, () -> getRecentModifiedFiles(workspace, userMessage));
        appendUnique(candidates, seenPaths, workspace, () -> searchBySemanticIndex(workspace, userMessage));
        appendUnique(candidates, seenPaths, workspace, () -> getFallbackFiles(workspace, codeGenType));
        return List.copyOf(candidates);
    }

    /** 追加{@code Unique}。 */
    private void appendUnique(List<EditFileCandidate> target,
                              Set<String> seenPaths,
                              GenerationWorkspace workspace,
                              Supplier<List<EditFileCandidate>> sourceSupplier) {
        if (target.size() >= properties.getMaxCandidateFiles()) {
            return;
        }
        List<EditFileCandidate> source = sourceSupplier.get();
        if (source == null || source.isEmpty()) {
            return;
        }
        for (EditFileCandidate candidate : source) {
            if (target.size() >= properties.getMaxCandidateFiles()) {
                return;
            }
            if (candidate == null) {
                continue;
            }
            workspaceFileService.resolveEditableFile(workspace, candidate.relativePath()).ifPresent(file -> {
                if (target.size() < properties.getMaxCandidateFiles() && seenPaths.add(file.relativePath())) {
                    target.add(normalizeCandidate(candidate, file));
                }
            });
        }
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

    /** 从输入中提取路径{@code Candidates}。 */
    private List<EditFileCandidate> extractPathCandidates(GenerationWorkspace workspace, String userMessage) {
        if (StrUtil.isBlank(userMessage)) {
            return List.of();
        }
        List<EditFileCandidate> candidates = new ArrayList<>();
        Matcher matcher = PATH_PATTERN.matcher(userMessage);
        while (matcher.find() && candidates.size() < properties.getMaxCandidateFiles()) {
            String relativePath = matcher.group(1);
            workspaceFileService.resolveEditableFile(workspace, relativePath).ifPresent(file -> candidates.add(
                    candidate(file, "explicit_path", 200, "The user explicitly referenced this file path", List.of(relativePath))
            ));
        }
        return candidates;
    }

    /** 从输入中提取{@code Bare}文件名称{@code Candidates}。 */
    private List<EditFileCandidate> extractBareFileNameCandidates(GenerationWorkspace workspace, String userMessage) {
        if (StrUtil.isBlank(userMessage)) {
            return List.of();
        }
        LinkedHashSet<String> fileNames = new LinkedHashSet<>();
        Matcher matcher = BARE_SOURCE_FILE_PATTERN.matcher(userMessage);
        while (matcher.find() && fileNames.size() < properties.getMaxCandidateFiles()) {
            fileNames.add(matcher.group(1));
        }
        if (fileNames.isEmpty()) {
            return List.of();
        }
        return workspaceFileService.scanEditableFiles(workspace, "").stream()
                .filter(file -> fileNames.contains(file.fileName()))
                .limit(properties.getMaxCandidateFiles())
                .map(file -> candidate(file, "explicit_file_name", 205,
                        "The user explicitly referenced this file name", List.of(file.relativePath())))
                .toList();
    }

    /** 获取并返回{@code Recent}{@code Modified}文件。 */
    private List<EditFileCandidate> getRecentModifiedFiles(GenerationWorkspace workspace, String userMessage) {
        if (StrUtil.isBlank(userMessage)) {
            return List.of();
        }
        List<EditFileCandidate> candidates = new ArrayList<>();
        try {
            List<String> recentFiles = editStatePersistenceService.getRelevantRecentFiles(
                    workspace.appId(), userMessage, Math.min(3, properties.getMaxCandidateFiles())
            );
            if (recentFiles == null) {
                return List.of();
            }
            for (String relativePath : recentFiles) {
                workspaceFileService.resolveEditableFile(workspace, relativePath).ifPresent(file -> candidates.add(
                        candidate(file, "recent_modified", 150, "This file was modified recently", List.of())
                ));
            }
        } catch (Exception e) {
            log.debug("Failed to load recently modified edit files", LogExceptionSanitizer.sanitize(e));
        }
        return candidates;
    }

    /** 搜索匹配的按语义索引。 */
    private List<EditFileCandidate> searchBySemanticIndex(GenerationWorkspace workspace, String userMessage) {
        if (StrUtil.isBlank(userMessage)) {
            return List.of();
        }
        List<EditFileCandidate> candidates = new ArrayList<>();
        try {
            List<String> suggestedFiles = semanticIndexService.suggestFiles(
                    workspace.canonicalRootPath(), userMessage, properties.getMaxCandidateFiles()
            );
            if (suggestedFiles == null) {
                return List.of();
            }
            for (String relativePath : suggestedFiles) {
                workspaceFileService.resolveEditableFile(workspace, relativePath).ifPresent(file -> candidates.add(
                        candidate(file, "semantic_search", 100, "The semantic index matched this file", List.of())
                ));
            }
        } catch (Exception e) {
            log.warn("Semantic edit-file lookup failed: {}", LogExceptionSanitizer.sanitizeMessage(e));
        }
        return candidates;
    }

    /** 获取并返回回退文件。 */
    private List<EditFileCandidate> getFallbackFiles(GenerationWorkspace workspace, CodeGenTypeEnum codeGenType) {
        if (codeGenType == null) {
            return List.of();
        }
        List<String> fallbackPaths = switch (codeGenType) {
            case HTML -> List.of("index.html");
            case MULTI_FILE -> List.of("index.html", "style.css", "script.js");
            case VUE_PROJECT -> List.of("src/App.vue", "src/main.ts", "src/main.js");
            case BACKEND_PROJECT -> List.of("cmd/server/main.go", "go.mod");
            case FULL_STACK_PROJECT -> List.of("frontend/src/App.vue", "frontend/src/main.ts");
        };
        List<EditFileCandidate> candidates = new ArrayList<>();
        for (String relativePath : fallbackPaths) {
            workspaceFileService.resolveEditableFile(workspace, relativePath).ifPresent(file -> candidates.add(
                    candidate(file, "fallback_entry", 50, "Fallback project entry file", List.of())
            ));
        }
        return candidates;
    }

    private EditFileCandidate candidate(EditWorkspaceFile file,
                                        String matchType,
                                        int score,
                                        String reason,
                                        List<String> matchedTerms) {
        return new EditFileCandidate(
                file.relativePath(), file.fileName(), matchType, score, reason, matchedTerms
        );
    }
}
