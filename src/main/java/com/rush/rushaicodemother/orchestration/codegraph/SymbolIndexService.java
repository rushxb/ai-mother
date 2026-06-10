package com.rush.rushaicodemother.orchestration.codegraph;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class SymbolIndexService {

    private final WorkspaceCodeGraphService codeGraphService;

    public SymbolIndexService(WorkspaceCodeGraphService codeGraphService) {
        this.codeGraphService = codeGraphService;
    }

    public Map<String, List<CodeGraphSymbol>> buildSymbolIndex(Path rootDir) {
        return codeGraphService.build(rootDir).symbolsByName();
    }

    public List<CodeGraphSymbol> search(Path rootDir, String symbolName, int limit) {
        return codeGraphService.findSymbols(rootDir, symbolName, limit);
    }
}
