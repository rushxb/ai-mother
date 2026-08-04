package com.rush.rushaicodemother.orchestration.experience;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.event.GenerationEvent;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationExperienceEventMapperTest {

    private final GenerationExperienceEventMapper mapper = new GenerationExperienceEventMapper();

    @Test
    void domainEventsMustMapToStableUserStagesWithoutLeakingInternalPayload() {
        GenerationStreamEvent event = mapper.map(new GenerationEvent(
                1L,
                2L,
                GenerationEventType.AGENT_EDIT_PLAN,
                "AGENT_EDIT Plan 阶段完成",
                Map.of("agent", "Planner", "reason", "cross_module_patch", "node", "planner"),
                Instant.parse("2026-07-31T00:00:00Z")
        )).orElseThrow();

        assertEquals(GenerationStreamEvent.GENERATION_STAGE, event.getType());
        assertEquals("正在确认修改范围", event.getText());
        assertEquals("planning", event.getData().get("stage"));
        assertEquals("user", event.getData().get("audience"));
        assertEquals(1, event.getData().get("contractVersion"));
        assertFalse(event.getData().containsKey("agent"));
        assertFalse(event.getData().containsKey("reason"));
        assertFalse(event.getData().containsKey("node"));
    }

    @Test
    void importantMilestonesMustCoverPreviewVerificationAndDelivery() {
        assertStage(GenerationEventType.FIRST_PREVIEW_READY, UserProgressStage.PREVIEW_READY);
        assertStage(GenerationEventType.VALIDATION_START, UserProgressStage.VERIFYING);
        assertStage(GenerationEventType.TASK_DONE, UserProgressStage.DELIVERED);
    }

    @Test
    void internalAgentEventsMustBecomeBoundedUserProgress() {
        GenerationStreamEvent event = mapper.map(GenerationStreamEvent.agentEvent("", Map.of(
                "agent", "DeadlinePolicy",
                "stage", "model_turn_admission",
                "status", "reserved_completion",
                "reason", "completion_window_reserved",
                "remainingMs", 10_000
        ))).orElseThrow();

        assertEquals(GenerationStreamEvent.GENERATION_STAGE, event.getType());
        assertEquals("verifying", event.getData().get("stage"));
        assertEquals("正在做质量校验", event.getText());
        assertFalse(String.valueOf(event.getData()).contains("DeadlinePolicy"));
        assertFalse(event.getData().containsKey("remainingMs"));
    }

    @Test
    void unknownInternalAgentStageMustUseSafeGenericProgress() {
        GenerationStreamEvent event = mapper.map(GenerationStreamEvent.agentEvent("internal", Map.of(
                "agent", "FutureAgent",
                "stage", "future_internal_node",
                "status", "running",
                "reason", "private"
        ))).orElseThrow();

        assertEquals("implementing", event.getData().get("stage"));
        assertEquals("正在生成或修改代码", event.getText());
        assertFalse(String.valueOf(event).contains("FutureAgent"));
        assertFalse(String.valueOf(event).contains("private"));
    }

    @Test
    void approvalEventMustKeepOnlyDecisionFieldsAndExposeChineseUserProgress() {
        GenerationStreamEvent event = mapper.map(GenerationStreamEvent.agentEvent("", Map.of(
                "agent", "PermissionPolicy",
                "stage", "approval",
                "status", "approval_required",
                "summary", "A destructive action requires approval",
                "taskId", "task-1",
                "action", "delete_file",
                "approvalId", "approval-1",
                "reason", "internal_policy",
                "remainingMs", 20_000
        ))).orElseThrow();

        assertEquals(GenerationStreamEvent.AGENT_EVENT, event.getType());
        assertEquals("操作确认", event.getData().get("agent"));
        assertEquals("需要你确认", event.getData().get("userProgressMessage"));
        assertEquals("awaiting_approval", event.getData().get("userProgressStage"));
        assertEquals("此操作需要你确认后才能继续", event.getData().get("summary"));
        assertEquals("task-1", event.getData().get("taskId"));
        assertFalse(event.getData().containsKey("reason"));
        assertFalse(event.getData().containsKey("remainingMs"));
    }

    @Test
    void failureOnlyDomainEventsMustNotPretendSuccessfulProgress() {
        assertTrue(mapper.map(new GenerationEvent(
                1L, 2L, GenerationEventType.TASK_FAILED, "内部失败", Map.of(), Instant.now()
        )).isEmpty());
    }

    private void assertStage(GenerationEventType type, UserProgressStage expectedStage) {
        GenerationStreamEvent event = mapper.map(new GenerationEvent(
                1L, 2L, type, "内部消息", Map.of(), Instant.now()
        )).orElseThrow();
        assertTrue(mapper.isUserProgressEvent(event));
        assertEquals(expectedStage.getCode(), mapper.userProgressStageCode(event));
        assertEquals(expectedStage.getDefaultMessage(), event.getText());
    }
}
