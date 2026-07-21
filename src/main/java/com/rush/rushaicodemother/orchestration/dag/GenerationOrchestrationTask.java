package com.rush.rushaicodemother.orchestration.dag;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
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

    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final int MIN_SUPPORTED_SCHEMA_VERSION = 1;

    private int schemaVersion = CURRENT_SCHEMA_VERSION;

    private String taskId;

    private long executionEpoch;

    private Long appId;

    private Long userId;

    private String requestHash;

    private String orchestrationMode;

    /** Hash of the ordered node declarations and their dependency edges. */
    private String dagFingerprint;

    private String status;

    private AgentRuntimeState runtimeState = AgentRuntimeState.INITIALIZED;

    private String currentNode;

    private String lastCompletedNode;

    private long checkpointVersion;

    private String terminationReason;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    private Map<String, String> nodeStatuses = new LinkedHashMap<>();

    private Map<String, Long> timings = new LinkedHashMap<>();

    private Map<String, GenerationArtifact> artifacts = new LinkedHashMap<>();

    private List<GenerationStreamEvent> events = new ArrayList<>();

    private String failureMessage;

    public static boolean supportsSchemaVersion(int schemaVersion) {
        return schemaVersion >= MIN_SUPPORTED_SCHEMA_VERSION
                && schemaVersion <= CURRENT_SCHEMA_VERSION;
    }
}
