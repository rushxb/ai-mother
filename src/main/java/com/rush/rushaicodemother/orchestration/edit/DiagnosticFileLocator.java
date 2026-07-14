package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Locates source files directly implicated by common build and runtime diagnostics. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosticFileLocator {

    private static final Pattern ROUTE_COMPONENT_NOT_FOUND_PATTERN = Pattern.compile(
            "Route component not found in src/views:\\s*([A-Za-z0-9_.$-]+)"
    );
    private static final Pattern VITE_IMPORT_ANALYSIS_PATTERN = Pattern.compile(
            "(?i)(?:failed to resolve import|rollup failed to resolve import)\\s+\"([^\"]+)\"\\s+from\\s+\"([^\"]+)\""
    );
    private static final Pattern DUPLICATE_IDENTIFIER_PATTERN = Pattern.compile(
            "Identifier\\s+['\"]([A-Za-z_$][\\w$]*)['\"]\\s+has already been declared"
    );

    private final WorkspaceSemanticIndexService semanticIndexService;
    private final EditWorkspaceFileService workspaceFileService;

    public List<EditFileCandidate> locate(GenerationWorkspace workspace, String userMessage) {
        if (workspace == null || StrUtil.isBlank(userMessage)) {
            return List.of();
        }

        List<EditFileCandidate> candidates = new ArrayList<>();
        addRouteDiagnosticFiles(candidates, workspace, userMessage);
        addImportDiagnosticFiles(candidates, workspace, userMessage);
        addDuplicateIdentifierFiles(candidates, workspace, userMessage);
        addPiniaDiagnosticFiles(candidates, workspace, userMessage);
        return candidates;
    }

    private void addRouteDiagnosticFiles(List<EditFileCandidate> candidates,
                                         GenerationWorkspace workspace,
                                         String userMessage) {
        if (!userMessage.contains("Route component not found in src/views")) {
            return;
        }
        addIfExists(candidates, workspace, "src/router/routeFactory.js", "route_diagnostic", 200,
                "Inspect route component resolution");
        addIfExists(candidates, workspace, "src/router/index.js", "route_diagnostic", 175,
                "Inspect the router entry point");
        addIfExists(candidates, workspace, "src/router/routeManifest.json", "route_diagnostic", 180,
                "Inspect the route manifest");
        addVueFiles(candidates, workspace, "src/views", "route_diagnostic", 170,
                "Inspect available view components");
        addVueFiles(candidates, workspace, "src/pages", "route_diagnostic", 160,
                "Inspect available page components");

        Matcher matcher = ROUTE_COMPONENT_NOT_FOUND_PATTERN.matcher(userMessage);
        if (matcher.find()) {
            addMatchingViewFiles(candidates, workspace, matcher.group(1));
        }
    }

    private void addImportDiagnosticFiles(List<EditFileCandidate> candidates,
                                          GenerationWorkspace workspace,
                                          String userMessage) {
        String normalizedMessage = userMessage.toLowerCase();
        if (!normalizedMessage.contains("failed to resolve import")
                && !normalizedMessage.contains("rollup failed to resolve import")) {
            return;
        }
        addIfExists(candidates, workspace, "src/main.js", "import_diagnostic", 195,
                "Inspect the JavaScript entry point");
        addIfExists(candidates, workspace, "src/main.ts", "import_diagnostic", 194,
                "Inspect the TypeScript entry point");
        addIfExists(candidates, workspace, "src/components/index.ts", "import_diagnostic", 150,
                "Inspect component exports");
        addVueFiles(candidates, workspace, "src/components", "import_diagnostic", 175,
                "Inspect component imports");

        Matcher matcher = VITE_IMPORT_ANALYSIS_PATTERN.matcher(userMessage);
        if (matcher.find()) {
            addImportErrorContextFiles(candidates, workspace, matcher.group(1), matcher.group(2));
        }
    }

    private void addDuplicateIdentifierFiles(List<EditFileCandidate> candidates,
                                             GenerationWorkspace workspace,
                                             String userMessage) {
        Matcher matcher = DUPLICATE_IDENTIFIER_PATTERN.matcher(userMessage);
        if (!matcher.find()) {
            return;
        }
        String identifier = matcher.group(1);
        addIndexedReferenceFiles(candidates, workspace, identifier, Set.of("vue", "js", "ts", "jsx", "tsx"),
                "identifier_index", 235, "The semantic index references the duplicate identifier");
        addFilesContainingText(candidates, workspace, identifier, "duplicate_identifier", 220,
                "The source file contains the duplicate identifier");
    }

    private void addPiniaDiagnosticFiles(List<EditFileCandidate> candidates,
                                         GenerationWorkspace workspace,
                                         String userMessage) {
        if (!userMessage.contains("getActivePinia()")) {
            return;
        }
        addIfExists(candidates, workspace, "src/main.js", "pinia_diagnostic", 200,
                "Inspect Pinia initialization in the JavaScript entry point");
        addIfExists(candidates, workspace, "src/main.ts", "pinia_diagnostic", 199,
                "Inspect Pinia initialization in the TypeScript entry point");
        addIfExists(candidates, workspace, "src/stores/index.ts", "pinia_diagnostic", 180,
                "Inspect the store entry point");
        addFiles(candidates, workspace, "src/stores", "pinia_diagnostic", 175,
                "Inspect store definitions", 20);
    }

    private void addFilesContainingText(List<EditFileCandidate> candidates,
                                        GenerationWorkspace workspace,
                                        String text,
                                        String matchType,
                                        int score,
                                        String reason) {
        if (StrUtil.isBlank(text)) {
            return;
        }
        workspaceFileService.scanIndexableFiles(workspace, "").stream()
                .filter(file -> workspaceFileService.readUtf8(workspace, file)
                        .map(content -> content.contains(text))
                        .orElse(false))
                .limit(16)
                .forEach(file -> addFile(candidates, file, matchType, score, reason));
    }

    private void addIndexedReferenceFiles(List<EditFileCandidate> candidates,
                                          GenerationWorkspace workspace,
                                          String token,
                                          Set<String> extensions,
                                          String matchType,
                                          int score,
                                          String reason) {
        try {
            List<String> indexedMatches = semanticIndexService.findFilesReferencing(
                    workspace.canonicalRootPath(), token, extensions, 12
            );
            for (String relativePath : indexedMatches) {
                addIfExists(candidates, workspace, relativePath, matchType, score, reason);
            }
        } catch (Exception e) {
            log.debug("Failed to locate indexed symbol references: {}", token, LogExceptionSanitizer.sanitize(e));
        }
    }

    private void addImportErrorContextFiles(List<EditFileCandidate> candidates,
                                            GenerationWorkspace workspace,
                                            String importPath,
                                            String fromPath) {
        if (StrUtil.isNotBlank(fromPath)) {
            addIfExists(candidates, workspace, normalizeProjectRelativePath(fromPath),
                    "import_source_match", 210, "The Vite diagnostic identifies this source file");
        }
        if (StrUtil.isBlank(importPath)) {
            return;
        }
        String normalizedImportPath = importPath.replace('\\', '/');
        if (normalizedImportPath.startsWith("@/")) {
            addIfExists(candidates, workspace, "src/" + normalizedImportPath.substring(2),
                    "import_target_match", 205, "The Vite diagnostic identifies this import target");
        }
        String fileName = extractFileName(normalizedImportPath);
        if (StrUtil.isNotBlank(fileName)) {
            addMatchingByFileName(candidates, workspace, fileName);
        }
    }

    private String normalizeProjectRelativePath(String path) {
        String normalized = StrUtil.blankToDefault(path, "").replace('\\', '/');
        int srcIndex = normalized.indexOf("/src/");
        if (srcIndex >= 0) {
            return normalized.substring(srcIndex + 1);
        }
        return normalized;
    }

    private void addMatchingByFileName(List<EditFileCandidate> candidates,
                                       GenerationWorkspace workspace,
                                       String fileName) {
        workspaceFileService.scanIndexableFiles(workspace, "").stream()
                .filter(file -> file.fileName().equalsIgnoreCase(fileName))
                .limit(12)
                .forEach(file -> addFile(candidates, file, "import_target_guess", 170,
                        "A workspace file has the unresolved import file name"));
    }

    private void addMatchingViewFiles(List<EditFileCandidate> candidates,
                                      GenerationWorkspace workspace,
                                      String componentName) {
        if (StrUtil.isBlank(componentName)) {
            return;
        }
        String normalizedName = normalizeComponentName(componentName);
        addMatchingVueFiles(candidates, workspace, "src/views", normalizedName,
                "route_component_match", 190, "A view file matches the missing route component");
        addMatchingVueFiles(candidates, workspace, "src/pages", normalizedName,
                "route_component_match", 185, "A page file matches the missing route component");
    }

    private void addMatchingVueFiles(List<EditFileCandidate> candidates,
                                     GenerationWorkspace workspace,
                                     String directory,
                                     String componentName,
                                     String matchType,
                                     int score,
                                     String reason) {
        workspaceFileService.scanIndexableFiles(workspace, directory).stream()
                .filter(file -> file.fileName().endsWith(".vue"))
                .filter(file -> componentNameMatches(componentName, file))
                .limit(12)
                .forEach(file -> addFile(candidates, file, matchType, score, reason));
    }

    private boolean componentNameMatches(String componentName, EditWorkspaceFile file) {
        String fileName = FileUtil.mainName(file.fileName());
        String relativePath = file.relativePath();
        int fileSeparator = relativePath.lastIndexOf('/');
        String parentPath = fileSeparator < 0 ? "" : relativePath.substring(0, fileSeparator);
        int parentSeparator = parentPath.lastIndexOf('/');
        String parentName = parentSeparator < 0 ? parentPath : parentPath.substring(parentSeparator + 1);
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

    private void addVueFiles(List<EditFileCandidate> candidates,
                             GenerationWorkspace workspace,
                             String directory,
                             String matchType,
                             int score,
                             String reason) {
        workspaceFileService.scanIndexableFiles(workspace, directory).stream()
                .filter(file -> file.fileName().endsWith(".vue"))
                .limit(20)
                .forEach(file -> addFile(candidates, file, matchType, score, reason));
    }

    private void addFiles(List<EditFileCandidate> candidates,
                          GenerationWorkspace workspace,
                          String directory,
                          String matchType,
                          int score,
                          String reason,
                          int limit) {
        workspaceFileService.scanIndexableFiles(workspace, directory).stream()
                .limit(limit)
                .forEach(file -> addFile(candidates, file, matchType, score, reason));
    }

    private void addIfExists(List<EditFileCandidate> candidates,
                             GenerationWorkspace workspace,
                             String relativePath,
                             String matchType,
                             int score,
                             String reason) {
        workspaceFileService.resolveIndexableFile(workspace, relativePath)
                .ifPresent(file -> addFile(candidates, file, matchType, score, reason));
    }

    private void addFile(List<EditFileCandidate> candidates,
                         EditWorkspaceFile file,
                         String matchType,
                         int score,
                         String reason) {
        candidates.add(new EditFileCandidate(
                file.relativePath(), file.fileName(), matchType, score, reason, List.of(file.relativePath())
        ));
    }

    private String extractFileName(String path) {
        if (StrUtil.isBlank(path)) {
            return "";
        }
        int separatorIndex = path.lastIndexOf('/');
        return separatorIndex < 0 ? path : path.substring(separatorIndex + 1);
    }
}
