package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.GenerationMemoryOutboxProperties;
import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import org.springframework.stereotype.Service;

/** 选择生成结果记忆的唯一写入路径。 */
@Service
public class GenerationOutcomeMemoryService {
    private static final String DIRECT_SOURCE = "generation_completion_fallback";

    private final GenerationSemanticMemoryService semanticMemoryService;
    private final MilvusMemoryProperties longTermProperties;
    private final GenerationMemoryOutboxProperties outboxProperties;

    public GenerationOutcomeMemoryService(GenerationSemanticMemoryService semanticMemoryService,
                                          MilvusMemoryProperties longTermProperties,
                                          GenerationMemoryOutboxProperties outboxProperties) {
        this.semanticMemoryService = semanticMemoryService;
        this.longTermProperties = longTermProperties;
        this.outboxProperties = outboxProperties;
    }

    public void remember(GenerationOutcomeMemoryRequest request) {
        if (request == null || durableOutboxEnabled()) {
            return;
        }
        GenerationOutcomeMemoryDocument document =
                GenerationOutcomeMemoryDocument.from(request, DIRECT_SOURCE);
        semanticMemoryService.rememberAsync(
                request.tenantId(),
                request.appId(),
                request.userId(),
                request.taskId(),
                document.type(),
                document.content(),
                document.metadata()
        );
    }

    private boolean durableOutboxEnabled() {
        return longTermProperties.isEnabled() && outboxProperties.isEnabled();
    }
}
