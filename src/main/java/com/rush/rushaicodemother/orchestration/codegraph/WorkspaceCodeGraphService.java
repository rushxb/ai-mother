package com.rush.rushaicodemother.orchestration.codegraph;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.tools.ProjectWorkspaceSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

@Slf4j
@Service
public class WorkspaceCodeGraphService {

    private static final Set<String> GRAPH_EXTENSIONS = Set.of("vue", "js", "ts", "jsx", "tsx", "mjs", "go", "sql");
    private static final int MAX_GRAPH_FILE_BYTES = 768 * 1024;

    private final CodeGraphAstParser parser;

    public WorkspaceCodeGraphService(CodeGraphAstParser parser) {
        this.parser = parser;
    }

    public WorkspaceCodeGraph build(Path rootDir) {
        if (rootDir == null) {
            return new WorkspaceCodeGraph("", List.of(), Map.of(), Map.of(), Map.of(), List.of("root_missing"));
        }
        Path normalizedRoot = rootDir.toAbsolutePath().normalize();
        List<CodeGraphFileNode> files = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        try {
            for (Path relativePath : ProjectWorkspaceSupport.listProjectFiles(normalizedRoot)) {
                String normalizedRelativePath = relativePath.toString().replace('\\', '/');
                if (!isGraphFile(normalizedRelativePath)) {
                    continue;
                }
                Path absolutePath = normalizedRoot.resolve(relativePath);
                if (!Files.isRegularFile(absolutePath) || Files.size(absolutePath) > MAX_GRAPH_FILE_BYTES) {
                    continue;
                }
                String content = FileUtil.readString(absolutePath.toFile(), StandardCharsets.UTF_8);
                files.add(parser.parse(normalizedRoot, normalizedRelativePath, content));
            }
        } catch (Exception e) {
            log.warn("构建 Code Graph 失败，rootDir: {}", normalizedRoot, e);
            diagnostics.add("code_graph_build_failed:" + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
        }
        return buildGraph(normalizedRoot, files, diagnostics);
    }

    public List<String> findReferencingFiles(Path rootDir, String relativePath, int limit) {
        if (StrUtil.isBlank(relativePath) || limit <= 0) {
            return List.of();
        }
        return build(rootDir).referencedBy(relativePath).stream().limit(limit).toList();
    }

    public List<CodeGraphSymbol> findSymbols(Path rootDir, String symbolName, int limit) {
        if (StrUtil.isBlank(symbolName) || limit <= 0) {
            return List.of();
        }
        return build(rootDir).symbolsByName().getOrDefault(symbolName, List.of()).stream()
                .limit(limit)
                .toList();
    }

    public List<String> findSchemaFieldImpact(Path rootDir, String fieldName, int limit) {
        if (StrUtil.isBlank(fieldName) || limit <= 0) {
            return List.of();
        }
        String normalizedField = fieldName.toLowerCase();
        LinkedHashSet<String> impacted = new LinkedHashSet<>();
        WorkspaceCodeGraph graph = build(rootDir);
        for (CodeGraphSymbol symbol : graph.symbolsByName().getOrDefault(fieldName, List.of())) {
            impacted.add(symbol.relativePath());
            impacted.addAll(graph.referencedBy(symbol.relativePath()));
        }
        for (CodeGraphFileNode file : graph.files()) {
            boolean matched = file.symbols().stream()
                    .anyMatch(symbol -> symbol.name().toLowerCase().contains(normalizedField));
            if (matched) {
                impacted.add(file.relativePath());
                impacted.addAll(graph.referencedBy(file.relativePath()));
            }
        }
        return impacted.stream().limit(limit).toList();
    }

    private WorkspaceCodeGraph buildGraph(Path rootDir, List<CodeGraphFileNode> files, List<String> diagnostics) {
        Map<String, List<String>> importsByFile = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> referencedBy = new LinkedHashMap<>();
        Map<String, List<CodeGraphSymbol>> symbolsByName = new LinkedHashMap<>();
        List<String> graphDiagnostics = new ArrayList<>(diagnostics);
        List<String> knownFiles = files.stream().map(CodeGraphFileNode::relativePath).toList();
        for (CodeGraphFileNode file : files) {
            List<String> imports = file.imports().stream()
                    .map(CodeGraphImport::resolvedFile)
                    .filter(StrUtil::isNotBlank)
                    .distinct()
                    .sorted()
                    .toList();
            importsByFile.put(file.relativePath(), imports);
            for (String importedFile : imports) {
                referencedBy.computeIfAbsent(importedFile, unused -> new LinkedHashSet<>()).add(file.relativePath());
            }
            for (CodeGraphSymbol symbol : file.symbols()) {
                symbolsByName.computeIfAbsent(symbol.name(), unused -> new ArrayList<>()).add(symbol);
            }
            graphDiagnostics.addAll(file.diagnostics());
        }
        for (String file : knownFiles) {
            referencedBy.computeIfAbsent(file, unused -> new LinkedHashSet<>());
        }
        Map<String, List<String>> referencedByFile = new LinkedHashMap<>();
        referencedBy.forEach((file, references) -> referencedByFile.put(file, references.stream().sorted().toList()));
        Map<String, List<CodeGraphSymbol>> immutableSymbols = new LinkedHashMap<>();
        symbolsByName.forEach((name, symbols) -> immutableSymbols.put(name, symbols.stream()
                .sorted(Comparator.comparing(CodeGraphSymbol::relativePath))
                .toList()));
        return new WorkspaceCodeGraph(
                rootDir.toString(),
                files.stream().sorted(Comparator.comparing(CodeGraphFileNode::relativePath)).toList(),
                importsByFile,
                referencedByFile,
                immutableSymbols,
                graphDiagnostics.stream().filter(StrUtil::isNotBlank).distinct().toList()
        );
    }

    private boolean isGraphFile(String relativePath) {
        String normalized = StrUtil.blankToDefault(relativePath, "").replace('\\', '/');
        String extension = StrUtil.blankToDefault(FileUtil.extName(normalized), "").toLowerCase();
        return GRAPH_EXTENSIONS.contains(extension)
                && (normalized.startsWith("src/")
                || normalized.startsWith("backend/")
                || normalized.startsWith("cmd/")
                || normalized.startsWith("internal/")
                || normalized.startsWith("sql/")
                || normalized.startsWith("frontend/src/"));
    }
}
