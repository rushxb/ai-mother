package com.rush.rushaicodemother.orchestration.codegraph;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * ReferenceSearch服务实现。
 */
@Service
public class ReferenceSearchService {

    private final WorkspaceCodeGraphService codeGraphService;

    public ReferenceSearchService(WorkspaceCodeGraphService codeGraphService) {
        this.codeGraphService = codeGraphService;
    }

    public List<String> referencingFiles(Path rootDir, String relativePath, int limit) {
        return codeGraphService.findReferencingFiles(rootDir, relativePath, limit);
    }

    public List<String> schemaFieldImpact(Path rootDir, String fieldName, int limit) {
        return codeGraphService.findSchemaFieldImpact(rootDir, fieldName, limit);
    }
}
