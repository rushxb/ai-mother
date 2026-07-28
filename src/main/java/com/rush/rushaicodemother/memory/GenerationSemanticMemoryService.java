package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.MemoryExecutionConfiguration;
import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 嵌入、调用和异步保存语义记忆的应用程序服务。 */
@Slf4j
@Service
public class GenerationSemanticMemoryService {
    private final LongTermMemoryStore memoryStore;
    private final MemoryEmbeddingService embeddingService;
    private final MilvusMemoryProperties properties;
    private final TaskExecutor memoryTaskExecutor;

    public GenerationSemanticMemoryService(
            LongTermMemoryStore memoryStore,
            MemoryEmbeddingService embeddingService,
            MilvusMemoryProperties properties,
            @Qualifier(MemoryExecutionConfiguration.MEMORY_TASK_EXECUTOR) TaskExecutor memoryTaskExecutor
    ) {
        this.memoryStore = memoryStore;
        this.embeddingService = embeddingService;
        this.properties = properties;
        this.memoryTaskExecutor = memoryTaskExecutor;
    }

    /**
 * 返回{@code recall}。
 *
 * @param tenantId 租户编号
 * @param appId 应用编号
 * @param queryText {@code queryText} 对应的调用参数
 * @param types 待处理的 {@code types} 集合
 * @return 生成语义记忆集合
 */
    public List<SemanticMemoryHit> recall(Long tenantId,
                                          Long appId,
                                          String queryText,
                                          Set<MemoryType> types) {
        if (tenantId == null || tenantId <= 0 || appId == null || appId <= 0
                || queryText == null || queryText.isBlank()) {
            return List.of();
        }
        try {
            float[] queryEmbedding = embeddingService.embed(queryText);
            return memoryStore.search(new SemanticMemoryQuery(
                    tenantId,
                    appId,
                    queryEmbedding,
                    types,
                    properties.getDefaultTopK(),
                    properties.getMinimumScore()
            ));
        } catch (RuntimeException failure) {
            log.warn("Semantic memory recall failed, appId: {}, error: {}",
                    appId, LogExceptionSanitizer.sanitizeMessage(failure));
            return List.of();
        }
    }

    /**
 * 处理记录异步。
 *
 * @param tenantId 租户编号
 * @param appId 应用编号
 * @param userId 用户编号
 * @param taskId 任务编号
 * @param type 目标类型
 * @param content 文件或消息内容
 * @param metadata 元数据
 */
    public void rememberAsync(Long tenantId,
                              Long appId,
                              Long userId,
                              String taskId,
                              MemoryType type,
                              String content,
                              Map<String, Object> metadata) {
        if (tenantId == null || tenantId <= 0 || appId == null || appId <= 0
                || userId == null || userId <= 0
                || taskId == null || taskId.isBlank() || type == null
                || content == null || content.isBlank()) {
            return;
        }
        try {
            memoryTaskExecutor.execute(() -> rememberSafely(
                    tenantId, appId, userId, taskId, type, content, metadata));
        } catch (RuntimeException rejection) {
            log.warn("Semantic memory persistence task rejected, taskId: {}, error: {}",
                    taskId, LogExceptionSanitizer.sanitizeMessage(rejection));
        }
    }

    /**
 * 处理记录立即。
 *
 * @param tenantId 租户编号
 * @param appId 应用编号
 * @param userId 用户编号
 * @param taskId 任务编号
 * @param type 目标类型
 * @param content 文件或消息内容
 * @param metadata 元数据
 */
    public void rememberNow(Long tenantId,
                            Long appId,
                            Long userId,
                            String taskId,
                            MemoryType type,
                            String content,
                            Map<String, Object> metadata) {
        if (tenantId == null || tenantId <= 0 || appId == null || appId <= 0
                || userId == null || userId <= 0
                || taskId == null || taskId.isBlank() || type == null
                || content == null || content.isBlank()) {
            throw new IllegalArgumentException("semantic memory identity and content are required");
        }
        String sanitized = SemanticMemoryGovernancePolicy.sanitizeContent(content);
        if (sanitized.isBlank()) {
            throw new IllegalArgumentException("semantic memory content is empty after sanitization");
        }
        String memoryId = deterministicMemoryId(tenantId, appId, userId, taskId, type, sanitized);
        Map<String, Object> governedMetadata =
                SemanticMemoryGovernancePolicy.governMetadata(metadata, sanitized);
        memoryStore.upsert(new SemanticMemory(
                memoryId,
                tenantId,
                appId,
                userId,
                taskId,
                type,
                sanitized,
                governedMetadata,
                embeddingService.embed(sanitized),
                Instant.now()
        ));
    }

    /** 处理记录安全处理。 */
    private void rememberSafely(Long tenantId,
                                Long appId,
                                Long userId,
                                String taskId,
                                MemoryType type,
                                String content,
                                Map<String, Object> metadata) {
        try {
            rememberNow(tenantId, appId, userId, taskId, type, content, metadata);
        } catch (RuntimeException failure) {
            log.warn("Semantic memory persistence failed, taskId: {}, error: {}",
                    taskId, LogExceptionSanitizer.sanitizeMessage(failure));
        }
    }

    private String deterministicMemoryId(Long tenantId,
                                         Long appId,
                                         Long userId,
                                         String taskId,
                                         MemoryType type,
                                         String content) {
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(
                tenantId + ":" + appId + ":" + userId + ":" + taskId + ":"
                        + type.name() + ":" + content);
    }
}
