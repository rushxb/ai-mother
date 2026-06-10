package com.rush.rushaicodemother.orchestration.edit;

import java.util.List;
import java.util.Map;

public record AgentEditReadResult(
        String intent,
        List<EditFileCandidate> candidateFiles,
        EditContextPackage contextPackage,
        Map<String, List<String>> importRelations,
        List<String> referencedBy,
        List<String> symbols,
        List<String> graphDiagnostics,
        List<String> recentEdits,
        String riskLevel
) {

    public boolean isEmpty() {
        return contextPackage == null || contextPackage.isEmpty();
    }

    public List<String> selectedFiles() {
        return contextPackage == null || contextPackage.candidates() == null
                ? List.of()
                : contextPackage.candidates().stream().map(EditFileCandidate::relativePath).toList();
    }
}
