package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 生成会话锁、容量和回放清理模型的生产资源门禁。 */
class GenerationSessionResourceBoundaryArchitectureTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path ORCHESTRATION_ROOT = PROJECT_ROOT.resolve(Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration"
    ));

    @Test
    void sessionRegistryMustUseBoundedStripesAndSingleSweepCleanup() throws IOException {
        String registry = Files.readString(ORCHESTRATION_ROOT.resolve("GenerationSessionRegistry.java"));
        String scheduling = Files.readString(ORCHESTRATION_ROOT.resolve("GenerationSessionCleanupConfiguration.java"));

        assertTrue(registry.contains("Object[] lockStripes"));
        assertTrue(registry.contains("maxTrackedSessions"));
        assertTrue(registry.contains("retainForReplay"));
        assertTrue(registry.contains("removeExpiredSessions"));
        assertFalse(registry.contains("Map<Long, Object>"));
        assertFalse(registry.contains("computeIfAbsent"));
        assertFalse(registry.contains("CompletableFuture.delayedExecutor"));
        assertTrue(scheduling.contains("addFixedDelayTask"));
        assertTrue(scheduling.contains("getCleanupInterval()"));
    }

    @Test
    void replayPolicyMustRemainCentralizedAndInternallyFixed() throws IOException {
        String properties = Files.readString(ORCHESTRATION_ROOT.resolve("GenerationSessionProperties.java"));
        Path pipelineRoot = ORCHESTRATION_ROOT.resolve("pipeline");
        String pipelineExecutor = Files.readString(pipelineRoot.resolve("GenerationPipelineExecutor.java"));

        assertFalse(properties.contains("@ConfigurationProperties"));
        assertTrue(properties.contains("@Validated"));
        assertTrue(properties.contains("public static final int LOCK_STRIPES"));
        assertTrue(properties.contains("public static final int MAX_TRACKED_SESSIONS"));
        assertTrue(properties.contains("public static final Duration COMPLETED_REPLAY_RETENTION"));
        assertTrue(properties.contains("public static final Duration CLEANUP_INTERVAL"));

        for (String pipeline : List.of(
                "SlotFillGenerationPipeline.java",
                "LightweightEditGenerationPipeline.java",
                "AgentEditGenerationPipeline.java"
        )) {
            String source = Files.readString(pipelineRoot.resolve(pipeline));
            assertFalse(source.contains("retainForReplay"));
            assertFalse(source.contains("COMPLETED_SESSION_REPLAY_SECONDS"));
            assertFalse(source.contains("cleanupLater"));
        }
        assertTrue(pipelineExecutor.contains("retainForReplay"));
    }
}
