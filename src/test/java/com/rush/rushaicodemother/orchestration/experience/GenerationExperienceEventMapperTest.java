package com.rush.rushaicodemother.orchestration.experience;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.event.GenerationEvent;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * 成功路径上，「已可预览」之后不得再宣称「正在生成或修改代码」。
     *
     * <p>这里按 EXPERT 成功链路的真实发事件顺序回放：暂定预览（dev server 就绪）→ 运行时验证 →
     * 收尾产出差异摘要 / 补丁摘要 / 残留审查 / 提交 → 已验证预览。收尾四个事件由
     * {@code HeavyGenerationFinalizationService} 发出，此时代码早已写完，若归入实现期，
     * 用户会先看到「已可预览」再看到「正在生成或修改代码」，读起来像生成又重启了。</p>
     *
     * <p>刻意只覆盖成功路径：自动修复轮次确实会重写代码，那里退回实现期是诚实的，
     * 因此不能用「阶段单调不回退」这种一刀切门禁来防这个缺陷。</p>
     */
    @Test
    void finalizationStagesMustNotClaimCodeIsStillBeingWrittenAfterPreviewIsReady() {
        List<String> stages = new ArrayList<>();
        stages.add(userStageOf(new GenerationEvent(
                1L, 2L, GenerationEventType.FIRST_PREVIEW_READY, "暂定预览已就绪", Map.of(), Instant.now())));
        for (String finalizationStage : List.of("diff", "patch", "orphan_review", "commit")) {
            stages.add(userStageOf(GenerationStreamEvent.agentEvent("", Map.of(
                    "agent", "Orchestrator",
                    "stage", finalizationStage,
                    "status", "success"
            ))));
        }
        stages.add(userStageOf(new GenerationEvent(
                1L, 2L, GenerationEventType.FIRST_PREVIEW_READY, "已验证预览已就绪", Map.of(), Instant.now())));

        int firstPreviewIndex = stages.indexOf(UserProgressStage.PREVIEW_READY.getCode());
        assertTrue(firstPreviewIndex >= 0, "首预览事件必须映射为可预览阶段");
        assertFalse(
                stages.subList(firstPreviewIndex, stages.size())
                        .contains(UserProgressStage.IMPLEMENTING.getCode()),
                "可预览之后不得回到实现期，实际阶段序列: " + stages);
    }

    /** 返回该事件对应的用户阶段编码，便于按序列断言。 */
    private String userStageOf(GenerationEvent event) {
        return mapper.userProgressStageCode(mapper.map(event).orElseThrow());
    }

    /** 返回该流事件对应的用户阶段编码，便于按序列断言。 */
    private String userStageOf(GenerationStreamEvent event) {
        return mapper.userProgressStageCode(mapper.map(event).orElseThrow());
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
