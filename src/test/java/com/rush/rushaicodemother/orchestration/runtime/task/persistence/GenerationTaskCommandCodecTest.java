package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.decision.GenerationMutability;
import com.rush.rushaicodemother.orchestration.decision.GenerationPreflightUsage;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.decision.GenerationToolPermissionProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentAmbiguitySignal;
import com.rush.rushaicodemother.orchestration.intent.IntentDestructiveRisk;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.intent.IntentValidationRisk;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionCode;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationTaskCommandCodecTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @Test
    void roundTripPreservesStructuredRoutingReasonAndSlaEnvelope() {
        Instant submittedAt = Instant.parse("2026-07-17T00:00:00Z");
        GenerationSlaEnvelope envelope = envelope();
        GenerationTraceContext traceContext = new GenerationTraceContext(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "vendor=value"
        );
        GenerationTaskCommand command = new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                "task-codec", 1L, 2L, 100L, "build app", CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.CREATE, 0.95, "template first", FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD, "",
                GenerationRoutingDecisionCode.CREATE_TEMPLATE_FIRST, envelope, traceContext,
                submittedAt, envelope.totalDeadline(submittedAt),
                GenerationResourceRequirements.ofDatabaseRequirement(true),
                profile(),
                plan(envelope),
                GenerationPlanningVariant.COMPACT_PLAN,
                scenarioDecision(),
                new GenerationPreflightUsage(1, 1, 2));

        GenerationTaskCommand restored = GenerationTaskCommandCodec.fromJson(
                GenerationTaskCommandCodec.toJson(command));

        assertEquals(GenerationRoutingDecisionCode.CREATE_TEMPLATE_FIRST, restored.routingDecisionCode());
        assertEquals("create-test", restored.slaEnvelope().profile());
        assertEquals(Duration.ofSeconds(45),
                restored.slaEnvelope().firstPreviewCompletionReserve());
        assertEquals(2, restored.slaEnvelope().toLimits().limit(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(100L, restored.tenantId());
        assertEquals(traceContext, restored.traceContext());
        assertEquals(GenerationResourceRequirements.ofDatabaseRequirement(true),
                restored.resourceRequirements());
        assertEquals(profile(), restored.intentProfile());
        assertEquals(plan(envelope), restored.executionPlan());
        assertEquals(GenerationPlanningVariant.COMPACT_PLAN, restored.planningVariant());
        assertEquals(scenarioDecision(), restored.scenarioDecision());
        assertEquals(new GenerationPreflightUsage(1, 1, 2), restored.preflightUsage());
    }

    @Test
    void oldCommandJsonWithoutNewFieldsRemainsReadable() {
        String json = """
                {"schemaVersion":1,"taskId":"legacy-task","appId":1,"userId":2,
                "userPrompt":"legacy","codeGenType":"VUE_PROJECT","mode":"AGENT_EDIT",
                "routingConfidence":0.7,"routingReason":"legacy","fallbackPolicy":"NONE",
                "expectedValidationLevel":"BUILD","fallbackReason":"",
                "submittedAt":"2026-07-17T00:00:00Z","deadlineAt":"2026-07-17T00:10:00Z"}
                """;

        GenerationTaskCommand restored = GenerationTaskCommandCodec.fromJson(json);

        assertEquals(GenerationRoutingDecisionCode.UNKNOWN, restored.routingDecisionCode());
        assertNull(restored.slaEnvelope());
        assertNull(restored.tenantId());
        assertEquals(GenerationTraceContext.empty(), restored.traceContext());
        assertEquals(GenerationResourceRequirements.none(), restored.resourceRequirements());
        assertEquals(IntentProfile.unknown(), restored.intentProfile());
        assertNull(restored.executionPlan());
        assertEquals(GenerationPlanningVariant.CURRENT_DAG, restored.planningVariant());
        assertNotNull(restored.scenarioDecision());
        assertEquals("legacy-task-command-v1", restored.scenarioDecision().ruleVersion());
        assertEquals(GenerationPreflightUsage.none(), restored.preflightUsage());
    }

    @Test
    void currentSchemaWithoutFrozenScenarioDecisionMustFailClosed() throws Exception {
        ObjectNode payload = completeCommandPayload();
        payload.remove("scenarioDecision");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> GenerationTaskCommandCodec.fromJson(JSON_MAPPER.writeValueAsString(payload)));

        assertEquals("任务命令 schema 10 缺少必需字段: scenarioDecision", exception.getMessage());
    }

    @Test
    void currentSchemaWithoutPreflightUsageMustFailClosed() throws Exception {
        ObjectNode payload = completeCommandPayload();
        payload.remove("preflightUsage");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> GenerationTaskCommandCodec.fromJson(JSON_MAPPER.writeValueAsString(payload)));

        assertEquals("任务命令 schema 10 缺少必需字段: preflightUsage", exception.getMessage());
    }

    @Test
    void currentSchemaWithoutFrozenExecutionPlanMustFailClosed() throws Exception {
        ObjectNode payload = completeCommandPayload();
        payload.remove("executionPlan");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> GenerationTaskCommandCodec.fromJson(JSON_MAPPER.writeValueAsString(payload)));

        assertEquals("任务命令 schema 10 缺少必需字段: executionPlan", exception.getMessage());
    }

    @Test
    void currentSchemaWithoutPlanningVariantMustFailClosed() throws Exception {
        ObjectNode payload = completeCommandPayload();
        payload.remove("planningVariant");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> GenerationTaskCommandCodec.fromJson(JSON_MAPPER.writeValueAsString(payload)));

        assertEquals("任务命令 schema 10 缺少必需字段: planningVariant", exception.getMessage());
    }

    @Test
    void textualSchemaVersionMustNotBypassRequiredFieldValidation() throws Exception {
        ObjectNode payload = completeCommandPayload();
        payload.put("schemaVersion", "10");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> GenerationTaskCommandCodec.fromJson(JSON_MAPPER.writeValueAsString(payload)));

        assertEquals("任务命令 schemaVersion 必须是整数", exception.getMessage());
    }

    @Test
    void schemaNineWithoutPreflightUsageRemainsReadable() throws Exception {
        ObjectNode payload = completeCommandPayload();
        payload.put("schemaVersion", 9);
        payload.remove("preflightUsage");

        GenerationTaskCommand restored = GenerationTaskCommandCodec.fromJson(
                JSON_MAPPER.writeValueAsString(payload));

        assertEquals(9, restored.schemaVersion());
        assertEquals(GenerationPreflightUsage.none(), restored.preflightUsage());
        assertEquals(scenarioDecision(), restored.scenarioDecision());
    }

    @Test
    void explicitProjectTypeHintMustPersistOnlyResolvedTarget() {
        Instant submittedAt = Instant.parse("2026-07-17T00:00:00Z");
        GenerationSlaEnvelope envelope = envelope();
        IntentProfile profile = new IntentProfile(
                IntentOperationType.CREATE,
                Set.of(IntentAffectedScope.FRONTEND),
                IntentSemanticComplexity.MEDIUM,
                false,
                false,
                IntentDestructiveRisk.LOW,
                6,
                IntentValidationRisk.MEDIUM,
                0.96,
                IntentAmbiguitySignal.resolved(),
                CodeGenTypeEnum.VUE_PROJECT);
        GenerationScenarioDecision scenarioDecision = new GenerationScenarioDecision(
                profile,
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMutability.WRITE,
                GenerationResourceRequirements.none(),
                new GenerationModeDecision(
                        GenerationMode.CREATE,
                        0.95,
                        "template first",
                        FallbackPolicy.NONE,
                        ExpectedValidationLevel.BUILD,
                        "",
                        GenerationRoutingDecisionCode.CREATE_TEMPLATE_FIRST),
                GenerationToolPermissionProfile.WRITE_FENCED,
                "intent-lexical/test",
                "b".repeat(64));
        GenerationTaskCommand command = new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                "task-explicit-target", 1L, 2L, 100L, "upgrade to Vue",
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.CREATE, 0.95, "template first", FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD, "",
                GenerationRoutingDecisionCode.CREATE_TEMPLATE_FIRST, envelope,
                GenerationTraceContext.empty(), submittedAt, envelope.totalDeadline(submittedAt),
                GenerationResourceRequirements.none(), profile, plan(envelope),
                GenerationPlanningVariant.COMPACT_PLAN, scenarioDecision,
                GenerationPreflightUsage.none());

        String json = GenerationTaskCommandCodec.toJson(command);
        GenerationTaskCommand restored = GenerationTaskCommandCodec.fromJson(json);

        assertFalse(json.contains("explicitProjectType"));
        assertEquals(CodeGenTypeEnum.VUE_PROJECT, restored.scenarioDecision().targetType());
        assertNull(restored.scenarioDecision().intentProfile().explicitProjectType());
    }

    @Test
    void schemaSixCommandWithoutExecutionPlanRemainsReadable() {
        String json = """
                {"schemaVersion":6,"taskId":"schema-six-task","appId":1,"userId":2,"tenantId":3,
                "userPrompt":"legacy","codeGenType":"VUE_PROJECT","mode":"AGENT_EDIT",
                "routingConfidence":0.7,"routingReason":"legacy","fallbackPolicy":"NONE",
                "expectedValidationLevel":"BUILD","fallbackReason":"",
                "submittedAt":"2026-07-17T00:00:00Z","deadlineAt":"2026-07-17T00:10:00Z"}
                """;

        GenerationTaskCommand restored = GenerationTaskCommandCodec.fromJson(json);

        assertEquals(6, restored.schemaVersion());
        assertNull(restored.executionPlan());
    }

    @Test
    void schemaEightCommandWithoutFrozenExecutionPlanRemainsReadable() {
        String json = """
                {"schemaVersion":8,"taskId":"schema-eight-task","appId":1,"userId":2,"tenantId":3,
                "userPrompt":"legacy","codeGenType":"VUE_PROJECT","mode":"AGENT_EDIT",
                "routingConfidence":0.7,"routingReason":"legacy","fallbackPolicy":"NONE",
                "expectedValidationLevel":"BUILD","fallbackReason":"",
                "planningVariant":"CURRENT_DAG",
                "submittedAt":"2026-07-17T00:00:00Z","deadlineAt":"2026-07-17T00:10:00Z"}
                """;

        GenerationTaskCommand restored = GenerationTaskCommandCodec.fromJson(json);

        assertEquals(8, restored.schemaVersion());
        assertEquals(GenerationPlanningVariant.CURRENT_DAG, restored.planningVariant());
        assertNull(restored.executionPlan());
        assertEquals("legacy-task-command-v8", restored.scenarioDecision().ruleVersion());
    }

    @Test
    void legacyResourceRequirementMustRemainAuthoritativeWhenProfileWasNotPersisted() {
        String json = """
                {"schemaVersion":7,"taskId":"legacy-resource-task","appId":1,"userId":2,"tenantId":3,
                "userPrompt":"legacy","codeGenType":"FULL_STACK_PROJECT","mode":"AGENT_EDIT",
                "routingConfidence":0.7,"routingReason":"legacy","fallbackPolicy":"NONE",
                "expectedValidationLevel":"BUILD","fallbackReason":"",
                "resourceRequirements":{"databaseRequired":true},
                "submittedAt":"2026-07-17T00:00:00Z","deadlineAt":"2026-07-17T00:10:00Z"}
                """;

        GenerationTaskCommand restored = GenerationTaskCommandCodec.fromJson(json);

        assertEquals(GenerationResourceRequirements.ofDatabaseRequirement(true),
                restored.scenarioDecision().requiredResources());
        assertEquals(true, restored.scenarioDecision().intentProfile().requiresDatabase());
    }

    @Test
    void commandMustRejectExecutionPlanWhoseRouteDriftsFromPersistedRoute() {
        Instant submittedAt = Instant.parse("2026-07-17T00:00:00Z");
        GenerationSlaEnvelope envelope = envelope();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> command(submittedAt, envelope, GenerationMode.AGENT_EDIT, plan(envelope)));

        assertEquals("执行计划路由与命令路由不一致", exception.getMessage());
    }

    @Test
    void commandMustRejectExecutionPlanWhoseSlaDriftsFromPersistedEnvelope() {
        Instant submittedAt = Instant.parse("2026-07-17T00:00:00Z");
        GenerationSlaEnvelope persistedEnvelope = envelope("persisted");
        GenerationExecutionPlan executionPlan = plan(envelope("planned"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> command(
                        submittedAt,
                        persistedEnvelope,
                        GenerationMode.CREATE,
                        executionPlan));

        assertEquals("执行计划 SLA 与命令 SLA 不一致", exception.getMessage());
    }

    @Test
    void legacyModelAttemptBudgetMustMapToSeparatedRuntimeBudgets() {
        String json = """
                {"schemaVersion":3,"taskId":"legacy-budget-task","appId":1,"userId":2,
                "userPrompt":"legacy","codeGenType":"VUE_PROJECT","mode":"AGENT_EDIT",
                "routingConfidence":0.7,"routingReason":"legacy","fallbackPolicy":"NONE",
                "expectedValidationLevel":"BUILD","fallbackReason":"",
                "slaEnvelope":{"profile":"legacy-budget","firstPreviewTimeout":"PT1M",
                "totalTimeout":"PT10M","modelCallTimeout":"PT2M",
                "minimumOperationTimeout":"PT0.5S","budgets":{"MODEL_ATTEMPT":2,
                "TOOL_WRITE":10,"BUILD_EXECUTION":1,"REPAIR_ROUND":1},"reason":"legacy"},
                "submittedAt":"2026-07-17T00:00:00Z","deadlineAt":"2026-07-17T00:10:00Z"}
                """;

        GenerationTaskCommand restored = GenerationTaskCommandCodec.fromJson(json);

        assertEquals(2, restored.slaEnvelope().toLimits()
                .limit(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(16, restored.slaEnvelope().toLimits()
                .limit(GenerationBudgetKind.MODEL_TURN));
        assertEquals(4, restored.slaEnvelope().toLimits()
                .limit(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT));
        assertEquals(Duration.ofMillis(500),
                restored.slaEnvelope().firstPreviewCompletionReserve());
    }

    private GenerationTaskCommand command(
            Instant submittedAt,
            GenerationSlaEnvelope envelope,
            GenerationMode mode,
            GenerationExecutionPlan executionPlan) {
        return new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                "task-consistency", 1L, 2L, 100L, "build app", CodeGenTypeEnum.VUE_PROJECT,
                mode, 0.95, "template first", FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD, "",
                GenerationRoutingDecisionCode.CREATE_TEMPLATE_FIRST, envelope,
                GenerationTraceContext.empty(), submittedAt, envelope.totalDeadline(submittedAt),
                GenerationResourceRequirements.none(), profile(), executionPlan
        );
    }

    private ObjectNode completeCommandPayload() throws Exception {
        Instant submittedAt = Instant.parse("2026-07-17T00:00:00Z");
        GenerationSlaEnvelope envelope = envelope();
        GenerationTaskCommand command = completeCommand(submittedAt, envelope);
        return (ObjectNode) JSON_MAPPER.readTree(GenerationTaskCommandCodec.toJson(command));
    }

    private GenerationTaskCommand completeCommand(Instant submittedAt,
                                                   GenerationSlaEnvelope envelope) {
        return new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                "task-complete", 1L, 2L, 100L, "build app", CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.CREATE, 0.95, "template first", FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD, "",
                GenerationRoutingDecisionCode.CREATE_TEMPLATE_FIRST, envelope,
                GenerationTraceContext.empty(), submittedAt, envelope.totalDeadline(submittedAt),
                GenerationResourceRequirements.ofDatabaseRequirement(true), profile(), plan(envelope),
                GenerationPlanningVariant.COMPACT_PLAN, scenarioDecision(),
                GenerationPreflightUsage.none());
    }

    private IntentProfile profile() {
        return new IntentProfile(
                IntentOperationType.CREATE,
                Set.of(IntentAffectedScope.FRONTEND, IntentAffectedScope.DATABASE),
                IntentSemanticComplexity.MEDIUM,
                true,
                true,
                IntentDestructiveRisk.LOW,
                8,
                IntentValidationRisk.HIGH,
                0.91
        );
    }

    private GenerationScenarioDecision scenarioDecision() {
        return new GenerationScenarioDecision(
                profile(),
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMutability.WRITE,
                GenerationResourceRequirements.ofDatabaseRequirement(true),
                new com.rush.rushaicodemother.orchestration.router.GenerationModeDecision(
                        GenerationMode.CREATE,
                        0.95,
                        "template first",
                        FallbackPolicy.NONE,
                        ExpectedValidationLevel.BUILD,
                        "",
                        GenerationRoutingDecisionCode.CREATE_TEMPLATE_FIRST),
                GenerationToolPermissionProfile.WRITE_FENCED,
                "intent-lexical/test",
                "b".repeat(64));
    }

    private GenerationExecutionPlan plan(GenerationSlaEnvelope envelope) {
        return new GenerationExecutionPlan(
                new com.rush.rushaicodemother.orchestration.router.GenerationModeDecision(
                        GenerationMode.CREATE,
                        0.95,
                        "template first",
                        FallbackPolicy.NONE,
                        ExpectedValidationLevel.BUILD,
                        "",
                        GenerationRoutingDecisionCode.CREATE_TEMPLATE_FIRST),
                GenerationPerformanceProfile.speedFirst(),
                new GenerationExecutionPlan.ContextBudget(2_000, 1_500, 800, 64, 6, "gpt-4o", 1.15),
                new GenerationExecutionPlan.ToolPolicy(
                        15,
                        envelope.toLimits().limit(GenerationBudgetKind.TOOL_WRITE),
                        true,
                        true),
                GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.BUILD),
                new GenerationExecutionPlan.RepairBudget(
                        envelope.toLimits().limit(GenerationBudgetKind.REPAIR_ROUND), true),
                new GenerationExecutionPlan.CommitPolicy(true, true),
                new GenerationExecutionPlan.PreviewPolicy(
                        envelope.firstPreviewTimeout(), envelope.firstPreviewCompletionReserve()),
                envelope
        );
    }

    private GenerationSlaEnvelope envelope() {
        return envelope("create-test");
    }

    private GenerationSlaEnvelope envelope(String profile) {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 2);
        }
        return new GenerationSlaEnvelope(
                profile, Duration.ofMinutes(1), Duration.ofSeconds(45), Duration.ofMinutes(10),
                Duration.ofMinutes(2), Duration.ofMillis(500), Map.copyOf(budgets), "test");
    }
}
