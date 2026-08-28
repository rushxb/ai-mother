package com.rush.rushaicodemother.controller.support;

import com.rush.rushaicodemother.config.GenerationSseProperties;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.eventstream.SequencedGenerationEvent;
import com.rush.rushaicodemother.orchestration.experience.GenerationExperienceEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationSseEventMapperTest {

    private final GenerationSseEventMapper mapper = mapperWithHeartbeat(Duration.ofSeconds(15));

    @Test
    void domainEventsMustRetainTypeAndJsonPayloadBeforeDoneSignal() {
        List<ServerSentEvent<String>> mapped = mapper.map(
                        Flux.just(GenerationStreamEvent.aiDelta("hello")))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertNotNull(mapped);
        assertEquals(2, mapped.size());
        assertEquals(GenerationStreamEvent.AI_DELTA, mapped.getFirst().event());
        assertTrue(mapped.getFirst().data().contains("hello"));
        assertEquals("done", mapped.getLast().event());
        assertEquals("", mapped.getLast().data());
    }

    @Test
    void nullDomainStreamMustBeRejectedAtBoundary() {
        assertThrows(IllegalArgumentException.class, () -> mapper.map(null));
    }

    @Test
    void sequencedEventsMustExposeIdsGapRecoveryAndTerminalMarkerWithoutSyntheticDone() {
        List<ServerSentEvent<String>> mapped = mapper.mapSequenced(Flux.just(
                        SequencedGenerationEvent.gap(12L, 10L, 13L),
                        SequencedGenerationEvent.event(13L, GenerationStreamEvent.aiDelta("resumed")),
                        SequencedGenerationEvent.complete(14L)
                ))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertNotNull(mapped);
        assertEquals(3, mapped.size());
        assertEquals("12", mapped.getFirst().id());
        assertEquals(GenerationSseEventMapper.GENERATION_GAP_EVENT, mapped.getFirst().event());
        assertTrue(mapped.getFirst().data().contains("\"requestedSeq\":10"));
        assertTrue(mapped.getFirst().data().contains("\"firstAvailableSeq\":13"));
        assertEquals("13", mapped.get(1).id());
        assertEquals(GenerationStreamEvent.AI_DELTA, mapped.get(1).event());
        assertEquals("14", mapped.getLast().id());
        assertEquals("done", mapped.getLast().event());
    }

    @Test
    void sequencedInternalAgentEventsMustUseStableProgressContractAndPreserveSequenceGapsAfterDeduplication() {
        List<ServerSentEvent<String>> mapped = mapper.mapSequenced(Flux.just(
                        SequencedGenerationEvent.event(20L, GenerationStreamEvent.agentEvent("", Map.of(
                                "agent", "Planner",
                                "stage", "planning",
                                "status", "running",
                                "reason", "private-route"
                        ))),
                        SequencedGenerationEvent.event(21L, GenerationStreamEvent.aiDelta("visible")),
                        SequencedGenerationEvent.event(22L, GenerationStreamEvent.agentEvent("", Map.of(
                                "agent", "Context",
                                "stage", "planning",
                                "status", "done"
                        ))),
                        SequencedGenerationEvent.event(23L, GenerationStreamEvent.agentEvent("", Map.of(
                                "agent", "Code",
                                "stage", "codegen",
                                "status", "running"
                        ))),
                        SequencedGenerationEvent.complete(24L)
                ))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertNotNull(mapped);
        assertEquals(4, mapped.size());
        assertEquals(List.of("20", "21", "23", "24"), mapped.stream()
                .map(ServerSentEvent::id)
                .toList());
        assertEquals(GenerationStreamEvent.GENERATION_STAGE, mapped.getFirst().event());
        assertTrue(mapped.getFirst().data().contains("正在确认修改范围"));
        assertEquals(GenerationStreamEvent.AI_DELTA, mapped.get(1).event());
        assertEquals(GenerationStreamEvent.GENERATION_STAGE, mapped.get(2).event());
        assertTrue(mapped.get(2).data().contains("正在生成或修改代码"));
        assertEquals("done", mapped.getLast().event());
        assertTrue(mapped.stream().noneMatch(event -> event.data() != null
                && (event.data().contains("Planner")
                || event.data().contains("Context")
                || event.data().contains("private-route"))));
    }

    @Test
    void nullSequencedStreamMustBeRejectedAtBoundary() {
        assertThrows(IllegalArgumentException.class, () -> mapper.mapSequenced(null));
    }

    @Test
    void privateThinkingEventsMustNeverReachTheWire() {
        List<ServerSentEvent<String>> mapped = mapper.map(Flux.just(
                        GenerationStreamEvent.aiThinkingDelta("private chain of thought"),
                        GenerationStreamEvent.aiDelta("visible answer")
                ))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertNotNull(mapped);
        assertEquals(2, mapped.size());
        assertEquals(GenerationStreamEvent.AI_DELTA, mapped.getFirst().event());
        assertTrue(mapped.getFirst().data().contains("visible answer"));
        assertTrue(mapped.stream().noneMatch(event ->
                event.data() != null && event.data().contains("private chain of thought")));
    }

    @Test
    void wireBoundaryMustDropRawToolArgumentsAndResultsEvenWhenUpstreamBypassesHandler() {
        String secret = "wire-secret";
        List<ServerSentEvent<String>> mapped = mapper.map(Flux.just(
                        GenerationStreamEvent.toolResult("password=" + secret, Map.of(
                                "toolName", "readFile",
                                "arguments", "{\"password\":\"" + secret + "\"}",
                                "result", "password=" + secret,
                                "filePath", "src/App.vue"
                        ))
                ))
                .collectList()
                .block(Duration.ofSeconds(1));

        String payload = mapped.getFirst().data();
        assertFalse(payload.contains(secret));
        assertFalse(payload.contains("arguments"));
        assertFalse(payload.contains("\"result\""));
        assertTrue(payload.contains("readFile"));
        assertTrue(payload.contains("src/App.vue"));
    }

    @Test
    void heartbeatCommentsMustKeepIdleConnectionAliveAndStopAtCompletion() {
        GenerationSseEventMapper fastHeartbeatMapper = mapperWithHeartbeat(Duration.ofMillis(100));

        List<ServerSentEvent<String>> mapped = fastHeartbeatMapper.map(
                        Flux.just(GenerationStreamEvent.aiDelta("hello"))
                                .delaySubscription(Duration.ofMillis(250)))
                .collectList()
                .block(Duration.ofSeconds(2));

        assertNotNull(mapped);
        int domainEventIndex = mapped.size() - 2;
        assertTrue(domainEventIndex >= 2);
        for (int index = 0; index < domainEventIndex; index++) {
            assertEquals("heartbeat", mapped.get(index).comment());
        }
        assertEquals(GenerationStreamEvent.AI_DELTA, mapped.get(domainEventIndex).event());
        assertEquals("done", mapped.getLast().event());
    }


    @Test
    void internalAgentEventsMustBecomeDeduplicatedUserProgressWithoutLeakingAgentDetails() {
        List<ServerSentEvent<String>> mapped = mapper.map(Flux.just(
                        GenerationStreamEvent.agentEvent("", Map.of(
                                "agent", "Planner",
                                "stage", "planning",
                                "status", "running",
                                "reason", "internal-route"
                        )),
                        GenerationStreamEvent.aiDelta("visible"),
                        GenerationStreamEvent.agentEvent("", Map.of(
                                "agent", "Context",
                                "stage", "planning",
                                "status", "done",
                                "node", "context"
                        )),
                        GenerationStreamEvent.agentEvent("", Map.of(
                                "agent", "Code",
                                "stage", "codegen",
                                "status", "running"
                        ))
                ))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertNotNull(mapped);
        List<ServerSentEvent<String>> progressEvents = mapped.stream()
                .filter(event -> GenerationStreamEvent.GENERATION_STAGE.equals(event.event()))
                .toList();
        assertEquals(2, progressEvents.size());
        assertTrue(progressEvents.getFirst().data().contains("正在确认修改范围"));
        assertTrue(progressEvents.getLast().data().contains("正在生成或修改代码"));
        assertTrue(mapped.stream().noneMatch(event -> event.data() != null
                && (event.data().contains("Planner")
                || event.data().contains("Context")
                || event.data().contains("internal-route"))));
    }

    @Test
    void approvalEventMustRetainDecisionIdentityButUsePublicChineseLabels() {
        List<ServerSentEvent<String>> mapped = mapper.map(Flux.just(
                        GenerationStreamEvent.agentEvent("", Map.of(
                                "agent", "PermissionPolicy",
                                "stage", "approval",
                                "status", "approval_required",
                                "taskId", "task-1",
                                "action", "delete_file",
                                "approvalId", "approval-1",
                                "eventId", "approval:task-1:1:approval-1:approval_required",
                                "reason", "private-policy"
                        ))
                ))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertNotNull(mapped);
        String payload = mapped.getFirst().data();
        assertEquals(GenerationStreamEvent.AGENT_EVENT, mapped.getFirst().event());
        assertTrue(payload.contains("操作确认"));
        assertTrue(payload.contains("请批准或拒绝本次操作"));
        assertTrue(payload.contains("approval-1"));
        assertTrue(payload.contains("approval:task-1:1:approval-1:approval_required"));
        assertFalse(payload.contains("PermissionPolicy"));
        assertFalse(payload.contains("private-policy"));
    }
    private GenerationSseEventMapper mapperWithHeartbeat(Duration heartbeatInterval) {
        GenerationSseProperties properties = new GenerationSseProperties();
        properties.setHeartbeatInterval(heartbeatInterval);
        return new GenerationSseEventMapper(properties, new GenerationExperienceEventMapper());
    }
}
