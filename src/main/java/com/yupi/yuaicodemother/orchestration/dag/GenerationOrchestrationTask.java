package com.yupi.yuaicodemother.orchestration.dag;

import com.yupi.yuaicodemother.core.handler.GenerationStreamEvent;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可持久化的编排任务快照。
 */
@Data
public class GenerationOrchestrationTask {

    private String taskId;

    private Long appId;

    private String requestHash;

    private String status;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    private Map<String, String> nodeStatuses = new LinkedHashMap<>();

    private Map<String, Long> timings = new LinkedHashMap<>();

    private Map<String, GenerationArtifact> artifacts = new LinkedHashMap<>();

    private List<GenerationStreamEvent> events = new ArrayList<>();

    private String failureMessage;
}
