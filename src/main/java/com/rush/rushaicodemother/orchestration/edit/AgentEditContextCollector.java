package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.codegraph.CodeGraphSymbol;
import com.rush.rushaicodemother.orchestration.codegraph.WorkspaceCodeGraph;
import com.rush.rushaicodemother.orchestration.codegraph.WorkspaceCodeGraphService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentEditContextCollector {

    private final EditFileLocatorService editFileLocatorService;
    private final EditContextPackageBuilder editContextPackageBuilder;
    private final WorkspaceCodeGraphService codeGraphService;

    public AgentEditReadResult collect(GenerationWorkspace workspace, String userMessage, CodeGenTypeEnum codeGenType) {
        List<EditFileCandidate> candidates = editFileLocatorService.locate(workspace, userMessage, codeGenType);
        EditContextPackage contextPackage = editContextPackageBuilder.build(workspace, candidates);
        Path projectRoot = workspace == null ? null : workspace.canonicalRootPath();
        WorkspaceCodeGraph graph = codeGraphService.build(projectRoot);
        List<String> selectedFiles = contextPackage == null || contextPackage.candidates() == null
                ? List.of()
                : contextPackage.candidates().stream().map(EditFileCandidate::relativePath).toList();
        Map<String, List<String>> importRelations = selectedFiles.stream()
                .collect(Collectors.toMap(
                        path -> path,
                        path -> graph.importsByFile().getOrDefault(path, List.of()),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
        LinkedHashSet<String> referencedBy = new LinkedHashSet<>();
        for (String file : selectedFiles) {
            referencedBy.addAll(graph.referencedBy(file));
        }
        List<String> symbols = graph.files().stream()
                .filter(file -> selectedFiles.contains(file.relativePath()))
                .flatMap(file -> file.symbols().stream())
                .map(CodeGraphSymbol::name)
                .distinct()
                .limit(40)
                .toList();
        return new AgentEditReadResult(
                inferIntent(userMessage),
                candidates,
                contextPackage,
                importRelations,
                referencedBy.stream().toList(),
                symbols,
                graph.diagnostics(),
                List.of(),
                inferRiskLevel(userMessage, contextPackage)
        );
    }

    private String inferIntent(String userMessage) {
        String message = StrUtil.blankToDefault(userMessage, "").toLowerCase();
        if (message.contains("bug") || message.contains("报错") || message.contains("error") || message.contains("失败")) {
            return "bug_fix";
        }
        if (message.contains("新增") || message.contains("添加") || message.contains("实现")) {
            return "feature_addition";
        }
        if (message.contains("字段") || message.contains("api") || message.contains("接口") || message.contains("数据库")) {
            return "contract_change";
        }
        return "code_edit";
    }

    private String inferRiskLevel(String userMessage, EditContextPackage contextPackage) {
        String message = StrUtil.blankToDefault(userMessage, "").toLowerCase();
        int fileCount = contextPackage == null ? 0 : contextPackage.fileCount();
        if (message.contains("数据库") || message.contains("schema") || message.contains("go.mod") || fileCount > 5) {
            return "high";
        }
        if (message.contains("api") || message.contains("接口") || message.contains("跨") || fileCount > 2) {
            return "medium";
        }
        return "low";
    }
}
