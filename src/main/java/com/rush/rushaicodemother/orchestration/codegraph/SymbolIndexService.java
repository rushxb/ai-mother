package com.rush.rushaicodemother.orchestration.codegraph;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 符号Index服务实现。
 */
@Service
public class SymbolIndexService {

    private final WorkspaceCodeGraphService codeGraphService;

    public SymbolIndexService(WorkspaceCodeGraphService codeGraphService) {
        this.codeGraphService = codeGraphService;
    }

    /**
 * 构建并返回{@code Symbol}索引。
 *
 * @param rootDir {@code rootDir} 对应的调用参数
 * @return {@code Symbol}索引集合
 */
    public Map<String, List<CodeGraphSymbol>> buildSymbolIndex(Path rootDir) {
        return codeGraphService.build(rootDir).symbolsByName();
    }

    public List<CodeGraphSymbol> search(Path rootDir, String symbolName, int limit) {
        return codeGraphService.findSymbols(rootDir, symbolName, limit);
    }
}
