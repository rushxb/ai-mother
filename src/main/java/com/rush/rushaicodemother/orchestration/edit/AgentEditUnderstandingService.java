package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AgentEditUnderstandingService {

    private static final Set<String> PROTECTED_FILE_PREFIXES = Set.of(
            "package.json", "vite.config", "tsconfig", "go.mod", "go.sum", "Dockerfile"
    );

    public AgentEditUnderstanding understand(AgentEditReadResult readResult) {
        if (readResult == null || readResult.contextPackage() == null) {
            return new AgentEditUnderstanding("未读取到可分析上下文", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "high");
        }
        List<String> selectedFiles = readResult.selectedFiles();
        List<String> protectedFiles = selectedFiles.stream()
                .filter(this::isProtectedFile)
                .toList();
        List<String> modules = inferModules(readResult.contextPackage().fileContents());
        List<String> referencedBy = readResult.referencedBy() == null ? List.of() : readResult.referencedBy();
        List<String> symbols = readResult.symbols() == null ? List.of() : readResult.symbols();
        List<String> diagnostics = readResult.graphDiagnostics() == null ? List.of() : readResult.graphDiagnostics();
        String summary = "已读取 " + selectedFiles.size() + " 个候选文件，意图为 " + readResult.intent()
                + "，引用影响 " + referencedBy.size() + " 个文件，符号 " + symbols.size() + " 个，风险级别 " + readResult.riskLevel();
        return new AgentEditUnderstanding(summary, selectedFiles, protectedFiles, modules, referencedBy, symbols, diagnostics, readResult.riskLevel());
    }

    private boolean isProtectedFile(String relativePath) {
        String normalized = StrUtil.blankToDefault(relativePath, "").replace('\\', '/');
        String fileName = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        return PROTECTED_FILE_PREFIXES.stream().anyMatch(fileName::startsWith);
    }

    private List<String> inferModules(Map<String, String> fileContents) {
        if (fileContents == null || fileContents.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> modules = new LinkedHashSet<>();
        for (String path : fileContents.keySet()) {
            String normalized = path.replace('\\', '/');
            if (normalized.contains("/")) {
                modules.add(normalized.substring(0, normalized.indexOf('/')));
            } else {
                modules.add(".");
            }
        }
        return modules.stream().toList();
    }
}
