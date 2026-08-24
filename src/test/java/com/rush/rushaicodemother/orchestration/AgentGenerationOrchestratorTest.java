package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.artifact.ApiContractArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateArtifact;
import com.rush.rushaicodemother.orchestration.artifact.TemplateBootstrapArtifact;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.intent.IntentBusinessDomain;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.agent.ArchitectAgentNode;
import com.rush.rushaicodemother.orchestration.agent.BuildFixAgentNode;
import com.rush.rushaicodemother.orchestration.agent.CodeAgentNode;
import com.rush.rushaicodemother.orchestration.agent.ContextAgentNode;
import com.rush.rushaicodemother.orchestration.agent.GenerationAgentSupport;
import com.rush.rushaicodemother.orchestration.agent.PlannerAgentNode;
import com.rush.rushaicodemother.orchestration.agent.ReviewAgentNode;
import com.rush.rushaicodemother.orchestration.agent.TemplateAgentNode;
import com.rush.rushaicodemother.orchestration.dag.AgentRuntimeState;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentNode;
import com.rush.rushaicodemother.orchestration.dag.GenerationDagRecoveryException;
import com.rush.rushaicodemother.orchestration.dag.GenerationDagRunner;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTaskStore;
import com.rush.rushaicodemother.orchestration.planning.CompactPlanningGraphAdapter;
import com.rush.rushaicodemother.orchestration.planning.CurrentDagPlanningGraphAdapter;
import com.rush.rushaicodemother.orchestration.planning.GenerationPlanningGraphRegistry;
import com.rush.rushaicodemother.orchestration.planning.NoPlanningGraphAdapter;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationRollbackPointService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationSnapshotWorkspaceService;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotNamePolicy;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.TemplateServiceTestFixture;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import cn.hutool.core.io.FileUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemTestFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.rush.rushaicodemother.orchestration.agent.GenerationAgentTestFixture.codeAgentNode;
import static com.rush.rushaicodemother.orchestration.agent.GenerationAgentTestFixture.reviewAgentNode;
import static com.rush.rushaicodemother.orchestration.agent.GenerationAgentTestFixture.support;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentGenerationOrchestratorTest {

    @Test
    void shouldUpgradeToVueTemplateWithoutBuildFixForNewProject() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("runtime-task-heavy");
        when(taskStore.create(eq("runtime-task-heavy"), anyLong(), anyString())).thenReturn(task);

        GenerationAgentSupport support = support(codeOutputRoot("shared"));
        AgentGenerationOrchestrator orchestrator = buildOrchestrator(taskStore, support);

        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());

        GenerationOrchestrationRequest request = frozenRequest(
                app,
                "创建一个 Vue 后台管理面板",
                CodeGenTypeEnum.HTML,
                "update",
                false,
                CodeGenTypeEnum.VUE_PROJECT,
                "runtime-task-heavy"
        );

        GenerationOrchestrationResult result = orchestrator.prepare(request);

        assertEquals(CodeGenTypeEnum.VUE_PROJECT, result.targetType());
        assertEquals("runtime-task-heavy", result.taskId());
        verify(taskStore).create("runtime-task-heavy", 1L, "创建一个 Vue 后台管理面板");
        assertTrue(result.artifacts().containsKey("template_bootstrap"));
        assertEquals(Boolean.TRUE, result.artifacts().get("template_bootstrap").payload().get("bootstrapped"));
    }

    @Test
    void ablationVariantsMustUseOneCheckpointAndStillProduceGenerationSpec() {
        for (GenerationPlanningVariant variant : List.of(
                GenerationPlanningVariant.COMPACT_PLAN,
                GenerationPlanningVariant.NO_PLAN)) {
            GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
            GenerationOrchestrationTask task = new GenerationOrchestrationTask();
            task.setTaskId("task-" + variant.name().toLowerCase());
            when(taskStore.create(anyLong(), anyString())).thenReturn(task);
            GenerationAgentSupport support = support(codeOutputRoot("ablation-" + variant.name()));
            AgentGenerationOrchestrator orchestrator = buildOrchestrator(taskStore, support);
            App app = new App();
            app.setId(1L);
            app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
            GenerationOrchestrationRequest request = frozenRequest(
                    app,
                    "创建一个 Vue 管理页面",
                    CodeGenTypeEnum.HTML,
                    "create",
                    false,
                    CodeGenTypeEnum.VUE_PROJECT,
                    null,
                    variant,
                    ExpectedValidationLevel.FAST
            );

            GenerationOrchestrationResult result = orchestrator.prepare(request);

            String expectedNode = variant == GenerationPlanningVariant.COMPACT_PLAN
                    ? "compact_plan"
                    : "no_plan";
            assertEquals(List.of(expectedNode), result.timings().keySet().stream().toList());
            assertTrue(result.artifacts().containsKey("generation_spec"));
        }
    }

    @Test
    void noPlanningVariantMustConsumeFrozenScenarioTarget() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-no-plan-frozen-scenario");
        when(taskStore.create(anyLong(), anyString())).thenReturn(task);
        GenerationAgentSupport support = support(codeOutputRoot("no-plan-frozen-scenario"));
        AgentGenerationOrchestrator orchestrator = buildOrchestrator(taskStore, support);

        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        GenerationModeDecision route = new GenerationModeDecision(
                GenerationMode.HEAVY_EXPERT,
                0.91,
                "frozen scenario",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.EXPERT,
                ""
        );
        GenerationScenarioDecision scenarioDecision = GenerationScenarioDecision.restoreLegacy(
                IntentProfile.unknown(),
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                GenerationResourceRequirements.none(),
                route,
                10
        );
        GenerationOrchestrationRequest request = GenerationOrchestrationRequest.fromFrozenScenario(
                app,
                "把现有 Vue 项目升级为企业全栈应用",
                CodeGenTypeEnum.VUE_PROJECT,
                "update",
                true,
                null,
                null,
                null,
                GenerationPlanningVariant.NO_PLAN,
                scenarioDecision
        );

        GenerationOrchestrationResult result = orchestrator.prepare(request);

        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT, result.targetType());
    }

    @Test
    void shouldResumeCheckpointAndSkipCompletedPlannerNode() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationOrchestrationTask restoredTask = new GenerationOrchestrationTask();
        restoredTask.setTaskId("runtime-task-resume");
        restoredTask.setAppId(1L);
        restoredTask.setStatus("running");
        restoredTask.setRuntimeState(AgentRuntimeState.RUNNING);
        restoredTask.setOrchestrationMode("light");
        restoredTask.setLastCompletedNode("planner");
        restoredTask.setCheckpointVersion(1);
        restoredTask.getNodeStatuses().put("planner", "done");
        restoredTask.getTimings().put("planner", 12L);
        restoredTask.getArtifacts().put("requirements", GenerationArtifact.of(
                "requirements",
                "Planner",
                "requirements",
                Map.of(
                        "targetType", CodeGenTypeEnum.VUE_PROJECT.getValue(),
                        "upgradeRequired", true,
                        "patchFirst", false,
                        "requiresBuild", false,
                        "validationMode", "review_only",
                        "generationMode", "full_generation",
                        "goals", List.of("resume from planner checkpoint"),
                        "recipes", List.of(),
                        "skills", List.of(),
                        "indexHits", List.of()
                )
        ));
        restoredTask.getArtifacts().put(
                ApiContractArtifact.KEY,
                ApiContractArtifact.create(
                        false,
                        "继续生成 Vue 应用",
                        IntentBusinessDomain.GENERAL
                ).toArtifact()
        );
        when(taskStore.load(1L, "runtime-task-resume")).thenReturn(Optional.of(restoredTask));
        when(taskStore.matchesRequest(restoredTask, "继续生成 Vue 应用")).thenReturn(true);

        GenerationAgentSupport support = support(codeOutputRoot("resume"));
        PlannerAgentNode plannerNode = spy(new PlannerAgentNode(support));
        TemplateAgentNode templateNode = testTemplateAgentNode("resume");
        ContextAgentNode contextNode = new ContextAgentNode(support);
        ArchitectAgentNode architectNode = new ArchitectAgentNode();
        var codeNode = codeAgentNode();
        var reviewNode = reviewAgentNode();
        restoredTask.setDagFingerprint(fingerprint(List.of(
                plannerNode,
                templateNode,
                contextNode,
                architectNode,
                codeNode,
                reviewNode
        )));
        GenerationOrchestrationMetricsCollector metricsCollector =
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry());
        AgentGenerationOrchestrator orchestrator = new AgentGenerationOrchestrator(
                new GenerationDagRunner(taskStore, metricsCollector, mock(GenerationExecutionContextService.class)),
                taskStore,
                planningGraphRegistry(
                        plannerNode, templateNode, contextNode, architectNode,
                        codeNode, reviewNode, new BuildFixAgentNode()),
                metricsCollector,
                testRollbackPointService("resume")
        );

        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        GenerationOrchestrationRequest request = frozenRequest(
                app,
                "继续生成 Vue 应用",
                CodeGenTypeEnum.HTML,
                "update",
                false,
                CodeGenTypeEnum.VUE_PROJECT,
                "runtime-task-resume"
        );

        GenerationOrchestrationResult result = orchestrator.prepare(request);

        assertEquals("runtime-task-resume", result.taskId());
        assertEquals(CodeGenTypeEnum.VUE_PROJECT, result.targetType());
        assertTrue(result.artifacts().containsKey("generation_spec"));
        assertEquals(12L, result.timings().get("planner"));
        verify(taskStore).load(1L, "runtime-task-resume");
        verify(taskStore, never()).create(eq("runtime-task-resume"), anyLong(), anyString());
        verify(plannerNode, never()).execute(any());
    }

    @Test
    void completedPreparationCheckpointMustBeReusableWithoutRepeatingDagSideEffects() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("runtime-task-completed-resume");
        task.setAppId(1L);
        task.setStatus("running");
        when(taskStore.load(1L, "runtime-task-completed-resume"))
                .thenReturn(Optional.empty(), Optional.of(task));
        when(taskStore.create("runtime-task-completed-resume", 1L, "创建一个 Vue 应用"))
                .thenReturn(task);
        when(taskStore.matchesRequest(task, "创建一个 Vue 应用")).thenReturn(true);

        GenerationAgentSupport support = support(codeOutputRoot("completed-resume"));
        PlannerAgentNode plannerNode = spy(new PlannerAgentNode(support));
        TemplateAgentNode templateNode = testTemplateAgentNode("completed-resume");
        ContextAgentNode contextNode = new ContextAgentNode(support);
        ArchitectAgentNode architectNode = new ArchitectAgentNode();
        var codeNode = codeAgentNode();
        var reviewNode = reviewAgentNode();
        GenerationOrchestrationMetricsCollector metricsCollector =
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry());
        GenerationRollbackPointService rollbackPointService = spy(testRollbackPointService("completed-resume"));
        AgentGenerationOrchestrator orchestrator = new AgentGenerationOrchestrator(
                new GenerationDagRunner(taskStore, metricsCollector, mock(GenerationExecutionContextService.class)),
                taskStore,
                planningGraphRegistry(
                        plannerNode, templateNode, contextNode, architectNode,
                        codeNode, reviewNode, new BuildFixAgentNode()),
                metricsCollector,
                rollbackPointService
        );
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        GenerationOrchestrationRequest request = frozenRequest(
                app,
                "创建一个 Vue 应用",
                CodeGenTypeEnum.HTML,
                "create",
                false,
                CodeGenTypeEnum.VUE_PROJECT,
                "runtime-task-completed-resume"
        );

        GenerationOrchestrationResult first = orchestrator.prepare(request);
        GenerationOrchestrationResult resumed = orchestrator.prepare(request);

        assertEquals(AgentRuntimeState.COMPLETED, task.getRuntimeState());
        assertEquals(first.enhancedMessage(), resumed.enhancedMessage());
        assertEquals(first.targetType(), resumed.targetType());
        assertEquals(first.timings(), resumed.timings());
        verify(plannerNode, times(1)).execute(any());
        verify(rollbackPointService, times(1)).prepareRollbackPoint(
                any(GenerationOrchestrationRequest.class), any(CodeGenTypeEnum.class),
                eq("runtime-task-completed-resume"));
    }

    @Test
    void completedReviewCheckpointWithoutQualityGateMustFailClosed() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("runtime-task-missing-quality-gate");
        task.setAppId(1L);
        task.setStatus("running");
        when(taskStore.load(1L, "runtime-task-missing-quality-gate"))
                .thenReturn(Optional.empty(), Optional.of(task));
        when(taskStore.create("runtime-task-missing-quality-gate", 1L, "创建一个 Vue 应用"))
                .thenReturn(task);
        when(taskStore.matchesRequest(task, "创建一个 Vue 应用")).thenReturn(true);
        GenerationAgentSupport support = support(codeOutputRoot("missing-quality-gate"));
        AgentGenerationOrchestrator orchestrator = buildOrchestrator(taskStore, support);
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        GenerationOrchestrationRequest request = frozenRequest(
                app,
                "创建一个 Vue 应用",
                CodeGenTypeEnum.HTML,
                "create",
                false,
                CodeGenTypeEnum.VUE_PROJECT,
                "runtime-task-missing-quality-gate"
        );

        orchestrator.prepare(request);
        task.getArtifacts().remove(QualityGateArtifact.KEY);

        assertThrows(BusinessException.class, () -> orchestrator.prepare(request));
    }

    @Test
    void completedTemplateCheckpointWithoutBootstrapArtifactMustFailClosed() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("runtime-task-missing-template-artifact");
        task.setAppId(1L);
        task.setStatus("running");
        when(taskStore.load(1L, "runtime-task-missing-template-artifact"))
                .thenReturn(Optional.empty(), Optional.of(task));
        when(taskStore.create("runtime-task-missing-template-artifact", 1L, "创建一个 Vue 应用"))
                .thenReturn(task);
        when(taskStore.matchesRequest(task, "创建一个 Vue 应用")).thenReturn(true);
        GenerationAgentSupport support = support(codeOutputRoot("missing-template-artifact"));
        AgentGenerationOrchestrator orchestrator = buildOrchestrator(taskStore, support);
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        GenerationOrchestrationRequest request = frozenRequest(
                app,
                "创建一个 Vue 应用",
                CodeGenTypeEnum.HTML,
                "create",
                false,
                CodeGenTypeEnum.VUE_PROJECT,
                "runtime-task-missing-template-artifact"
        );

        orchestrator.prepare(request);
        task.getArtifacts().remove(TemplateBootstrapArtifact.KEY);

        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> orchestrator.prepare(request)
        );

        assertTrue(failure.getCause() instanceof GenerationDagRecoveryException);
    }

    @Test
    void completedCheckpointWithForeignRollbackPointMustFailClosed() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("runtime-task-foreign-rollback-point");
        task.setAppId(1L);
        task.setStatus("running");
        when(taskStore.load(1L, "runtime-task-foreign-rollback-point"))
                .thenReturn(Optional.empty(), Optional.of(task));
        when(taskStore.create("runtime-task-foreign-rollback-point", 1L, "创建一个 Vue 应用"))
                .thenReturn(task);
        when(taskStore.matchesRequest(task, "创建一个 Vue 应用")).thenReturn(true);
        GenerationAgentSupport support = support(codeOutputRoot("foreign-rollback-point"));
        AgentGenerationOrchestrator orchestrator = buildOrchestrator(taskStore, support);
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        GenerationOrchestrationRequest request = frozenRequest(
                app,
                "创建一个 Vue 应用",
                CodeGenTypeEnum.HTML,
                "create",
                false,
                CodeGenTypeEnum.VUE_PROJECT,
                "runtime-task-foreign-rollback-point"
        );

        orchestrator.prepare(request);
        GenerationArtifact rollbackPoint = task.getArtifacts().get("rollback_point");
        Map<String, Object> foreignPayload = new LinkedHashMap<>(rollbackPoint.payload());
        foreignPayload.put("taskId", "foreign-task");
        task.getArtifacts().put(
                "rollback_point",
                GenerationArtifact.of(
                        rollbackPoint.key(),
                        rollbackPoint.role(),
                        rollbackPoint.title(),
                        foreignPayload
                )
        );

        assertThrows(BusinessException.class, () -> orchestrator.prepare(request));
    }

    @Test
    void shouldRouteToHeavyPathForExistingProjectVueUpgrade() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-upgrade");
        when(taskStore.create(anyLong(), anyString())).thenReturn(task);

        GenerationAgentSupport support = support(codeOutputRoot("shared"));
        AgentGenerationOrchestrator orchestrator = buildOrchestrator(taskStore, support);

        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.MULTI_FILE.getValue());
        writeExistingProjectFile("shared", CodeGenTypeEnum.VUE_PROJECT, app.getId(), "src/App.vue", "<template>tool</template>");

        GenerationOrchestrationRequest request = frozenRequest(
                app,
                "做一个工具页",
                CodeGenTypeEnum.MULTI_FILE,
                "update",
                true,
                CodeGenTypeEnum.VUE_PROJECT,
                null,
                GenerationPlanningVariant.CURRENT_DAG,
                ExpectedValidationLevel.BUILD
        );

        GenerationOrchestrationResult result = orchestrator.prepare(request);

        assertEquals(CodeGenTypeEnum.VUE_PROJECT, result.targetType());
        assertTrue(result.artifacts().containsKey("buildfix_plan"));
    }

    @Test
    void shouldEnableBuildFixForBuildIntensiveHtmlRequest() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-build");
        when(taskStore.create(anyLong(), anyString())).thenReturn(task);

        GenerationAgentSupport support = support(codeOutputRoot("shared"));
        AgentGenerationOrchestrator orchestrator = buildOrchestrator(taskStore, support);

        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        writeExistingProjectFile("shared", CodeGenTypeEnum.HTML, app.getId(), "index.html", "<html><body>login</body></html>");

        GenerationOrchestrationRequest request = frozenRequest(
                app,
                "请补充打包和构建校验",
                CodeGenTypeEnum.HTML,
                "update",
                false,
                CodeGenTypeEnum.HTML,
                null,
                GenerationPlanningVariant.CURRENT_DAG,
                ExpectedValidationLevel.BUILD
        );

        GenerationOrchestrationResult result = orchestrator.prepare(request);

        assertTrue(result.artifacts().containsKey("buildfix_plan"));
        @SuppressWarnings("unchecked")
        Map<String, Object> buildfixPlan = result.artifacts().get("buildfix_plan").payload();
        assertEquals(Boolean.TRUE, buildfixPlan.get("enabled"));
    }

    @Test
    void shouldRecordOrchestrationMetrics() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-metrics");
        when(taskStore.create(anyLong(), anyString())).thenReturn(task);

        GenerationAgentSupport support = support(codeOutputRoot("metrics"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GenerationOrchestrationMetricsCollector metricsCollector = new GenerationOrchestrationMetricsCollector(meterRegistry);
        GenerationDagRunner dagRunner = new GenerationDagRunner(
                taskStore, metricsCollector, mock(GenerationExecutionContextService.class));
        GenerationRollbackPointService rollbackPointService = testRollbackPointService("metrics");
        AgentGenerationOrchestrator orchestrator = new AgentGenerationOrchestrator(
                dagRunner,
                taskStore,
                planningGraphRegistry(
                        new PlannerAgentNode(support),
                        testTemplateAgentNode("metrics"),
                        new ContextAgentNode(support),
                        new ArchitectAgentNode(),
                        codeAgentNode(),
                        reviewAgentNode(),
                        new BuildFixAgentNode()),
                metricsCollector,
                rollbackPointService
        );

        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        writeExistingProjectFile("metrics", CodeGenTypeEnum.HTML, app.getId(),
                "index.html", "<html><body>login</body></html>");

        GenerationOrchestrationRequest request = frozenRequest(
                app,
                "修改登录表单样式",
                CodeGenTypeEnum.HTML,
                "update",
                true,
                CodeGenTypeEnum.HTML,
                null
        );

        String orchestrationMode = request.scenarioDecision().routeDecision().mode()
                == GenerationMode.HEAVY_EXPERT ? "heavy" : "light";
        orchestrator.prepare(request);

        assertEquals(1.0, meterRegistry.counter(
                "generation_orchestration_runs_total",
                "orchestration_mode", orchestrationMode,
                "status", "started"
        ).count());
        assertEquals(1.0, meterRegistry.counter(
                "generation_orchestration_runs_total",
                "orchestration_mode", orchestrationMode,
                "status", "success"
        ).count());
        assertEquals(1, meterRegistry.find("generation_orchestration_node_duration_seconds")
                .tag("dag_node", "planner")
                .tag("status", "done")
                .timer()
                .count());
        assertEquals(1.0, meterRegistry.counter(
                "generation_orchestration_patch_first_total",
                "orchestration_mode", orchestrationMode,
                "enabled", "true"
        ).count());
        assertEquals(1, meterRegistry.find("generation_orchestration_context_chars")
                .tag("orchestration_mode", orchestrationMode)
                .summary()
                .count());
    }

    private AgentGenerationOrchestrator buildOrchestrator(GenerationOrchestrationTaskStore taskStore,
                                                          GenerationAgentSupport support) {
        GenerationOrchestrationMetricsCollector metricsCollector =
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry());
        GenerationDagRunner dagRunner = new GenerationDagRunner(
                taskStore, metricsCollector, mock(GenerationExecutionContextService.class));
        GenerationRollbackPointService rollbackPointService = testRollbackPointService("shared");
        return new AgentGenerationOrchestrator(
                dagRunner,
                taskStore,
                planningGraphRegistry(
                        new PlannerAgentNode(support),
                        testTemplateAgentNode("shared"),
                        new ContextAgentNode(support),
                        new ArchitectAgentNode(),
                        codeAgentNode(),
                        reviewAgentNode(),
                        new BuildFixAgentNode()),
                metricsCollector,
                rollbackPointService
        );
    }

    /** 使测试请求与生产 Heavy 入口共享同一份冻结场景事实。 */
    private GenerationOrchestrationRequest frozenRequest(
            App app,
            String userMessage,
            CodeGenTypeEnum currentType,
            String generatingStage,
            boolean hasGeneratedCode,
            CodeGenTypeEnum targetType,
            String taskId
    ) {
        return frozenRequest(
                app,
                userMessage,
                currentType,
                generatingStage,
                hasGeneratedCode,
                targetType,
                taskId,
                GenerationPlanningVariant.CURRENT_DAG,
                ExpectedValidationLevel.FAST
        );
    }

    private GenerationOrchestrationRequest frozenRequest(
            App app,
            String userMessage,
            CodeGenTypeEnum currentType,
            String generatingStage,
            boolean hasGeneratedCode,
            CodeGenTypeEnum targetType,
            String taskId,
            GenerationPlanningVariant planningVariant,
            ExpectedValidationLevel validationLevel
    ) {
        GenerationMode mode = validationLevel == ExpectedValidationLevel.FAST
                ? GenerationMode.LIGHT_EDIT
                : GenerationMode.HEAVY_EXPERT;
        GenerationModeDecision route = new GenerationModeDecision(
                mode,
                0.91,
                "test frozen scenario",
                FallbackPolicy.NONE,
                validationLevel,
                ""
        );
        GenerationScenarioDecision scenarioDecision = GenerationScenarioDecision.restoreLegacy(
                IntentProfile.unknown(),
                targetType,
                GenerationResourceRequirements.none(),
                route,
                10
        );
        return GenerationOrchestrationRequest.fromFrozenScenario(
                app,
                userMessage,
                currentType,
                generatingStage,
                hasGeneratedCode,
                null,
                null,
                taskId,
                planningVariant,
                scenarioDecision
        );
    }

    private GenerationPlanningGraphRegistry planningGraphRegistry(
            PlannerAgentNode planner,
            TemplateAgentNode template,
            ContextAgentNode context,
            ArchitectAgentNode architect,
            CodeAgentNode code,
            ReviewAgentNode review,
            BuildFixAgentNode buildFix
    ) {
        CurrentDagPlanningGraphAdapter currentDag = new CurrentDagPlanningGraphAdapter(
                planner, template, context, architect, code, review, buildFix);
        return new GenerationPlanningGraphRegistry(List.of(
                currentDag,
                new CompactPlanningGraphAdapter(currentDag),
                new NoPlanningGraphAdapter(template, context)
        ));
    }

    private GenerationRollbackPointService testRollbackPointService(String caseName) {
        Path root = Path.of("target", "test-workspaces", "rollback-orchestrator", caseName);
        FileUtil.del(root.toFile());
        CodeStorageProperties storageProperties = new CodeStorageProperties();
        storageProperties.setOutputRootDir(root.resolve("code_output"));
        storageProperties.setDeployRootDir(root.resolve("code_deploy"));
        storageProperties.setSnapshotRootDir(root.resolve("code_snapshot"));
        var fileSystemService = WorkspaceFileSystemTestFactory.create();
        var snapshotNamePolicy = new SnapshotNamePolicy();
        return new GenerationRollbackPointService(
                new GenerationWorkspaceService(storageProperties),
                new GenerationSnapshotWorkspaceService(storageProperties, fileSystemService, snapshotNamePolicy),
                fileSystemService,
                snapshotNamePolicy,
                mock(GenerationTaskFenceGuard.class),
                mock(GenerationExecutionContextService.class)
        );
    }

    private TemplateAgentNode testTemplateAgentNode(String caseName) {
        Path root = Path.of("target", "test-workspaces", "template-orchestrator", caseName);
        FileUtil.del(root.toFile());
        TemplateServiceTestFixture fixture = new TemplateServiceTestFixture(root.resolve("code_output"));
        return new TemplateAgentNode(fixture.templateBootstrapRegistry());
    }

    private void writeExistingProjectFile(String caseName,
                                          CodeGenTypeEnum type,
                                          Long appId,
                                          String relativePath,
                                          String content) {
        Path root = codeOutputRoot(caseName).resolve(type.getValue() + "_" + appId);
        FileUtil.writeUtf8String(content, root.resolve(relativePath).toFile());
    }

    private Path codeOutputRoot(String caseName) {
        return Path.of("target", "test-workspaces", "template-orchestrator", caseName,
                "code_output");
    }

    private String fingerprint(List<GenerationAgentNode> nodes) {
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < nodes.size(); index++) {
            GenerationAgentNode node = nodes.get(index);
            canonical.append(index).append('\u0000')
                    .append(node.key()).append('\u0000')
                    .append(node.agentName()).append('\u0000')
                    .append(node.stage()).append('\u0000')
                    .append(node.replayPolicy().name()).append('\u0000');
            node.dependencies().stream().sorted().forEach(dependency ->
                    canonical.append(dependency).append('\u0001'));
            canonical.append('\u0002');
        }
        return DigestUtil.sha256Hex(canonical.toString());
    }
}
