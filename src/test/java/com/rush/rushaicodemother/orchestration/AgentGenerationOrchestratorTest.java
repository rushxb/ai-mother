package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.agent.ArchitectAgentNode;
import com.rush.rushaicodemother.orchestration.agent.BuildFixAgentNode;
import com.rush.rushaicodemother.orchestration.agent.ContextAgentNode;
import com.rush.rushaicodemother.orchestration.agent.GenerationAgentSupport;
import com.rush.rushaicodemother.orchestration.agent.GenerationRoutingSupport;
import com.rush.rushaicodemother.orchestration.agent.PlannerAgentNode;
import com.rush.rushaicodemother.orchestration.agent.TemplateAgentNode;
import com.rush.rushaicodemother.orchestration.dag.AgentRuntimeState;
import com.rush.rushaicodemother.orchestration.dag.GenerationDagRunner;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTaskStore;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationRollbackPointService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationSnapshotWorkspaceService;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotNamePolicy;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.TemplateServiceTestFixture;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackPortAllocator;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemTestFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.rush.rushaicodemother.orchestration.agent.GenerationAgentTestFixture.codeAgentNode;
import static com.rush.rushaicodemother.orchestration.agent.GenerationAgentTestFixture.reviewAgentNode;
import static com.rush.rushaicodemother.orchestration.agent.GenerationAgentTestFixture.support;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentGenerationOrchestratorTest {

    @Test
    void shouldUpgradeToVueTemplateWithoutBuildFixForNewProject() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("runtime-task-heavy");
        when(taskStore.create(eq("runtime-task-heavy"), anyLong(), anyString())).thenReturn(task);

        GenerationAgentSupport support = support();
        GenerationRoutingSupport routingSupport = new GenerationRoutingSupport(support);
        AgentGenerationOrchestrator orchestrator = buildOrchestrator(taskStore, support, routingSupport);

        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());

        Function<String, CodeGenTypeEnum> routingFunction = prompt -> CodeGenTypeEnum.VUE_PROJECT;
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "创建一个 Vue 后台管理面板",
                CodeGenTypeEnum.HTML,
                "update",
                false,
                null,
                routingFunction,
                null,
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
        when(taskStore.load(1L, "runtime-task-resume")).thenReturn(Optional.of(restoredTask));
        when(taskStore.matchesRequest(restoredTask, "继续生成 Vue 应用")).thenReturn(true);

        GenerationAgentSupport support = support();
        GenerationRoutingSupport routingSupport = new GenerationRoutingSupport(support);
        PlannerAgentNode plannerNode = spy(new PlannerAgentNode(support, routingSupport));
        GenerationOrchestrationMetricsCollector metricsCollector =
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry());
        AgentGenerationOrchestrator orchestrator = new AgentGenerationOrchestrator(
                new GenerationDagRunner(taskStore, metricsCollector, mock(GenerationExecutionContextService.class)),
                taskStore,
                plannerNode,
                testTemplateAgentNode("resume"),
                new ContextAgentNode(support),
                new ArchitectAgentNode(support),
                codeAgentNode(),
                reviewAgentNode(),
                new BuildFixAgentNode(),
                routingSupport,
                metricsCollector,
                testRollbackPointService("resume")
        );

        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "继续生成 Vue 应用",
                CodeGenTypeEnum.HTML,
                "update",
                false,
                null,
                prompt -> CodeGenTypeEnum.VUE_PROJECT,
                null,
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
    void shouldRouteToHeavyPathForExistingProjectVueUpgrade() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-upgrade");
        when(taskStore.create(anyLong(), anyString())).thenReturn(task);

        GenerationAgentSupport support = support();
        GenerationRoutingSupport routingSupport = new GenerationRoutingSupport(support);
        AgentGenerationOrchestrator orchestrator = buildOrchestrator(taskStore, support, routingSupport);

        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.MULTI_FILE.getValue());
        writeExistingProjectFile("shared", CodeGenTypeEnum.VUE_PROJECT, app.getId(), "src/App.vue", "<template>tool</template>");

        Function<String, CodeGenTypeEnum> routingFunction = prompt -> CodeGenTypeEnum.VUE_PROJECT;
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "做一个工具页",
                CodeGenTypeEnum.MULTI_FILE,
                "update",
                true,
                null,
                routingFunction,
                null
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

        GenerationAgentSupport support = support();
        GenerationRoutingSupport routingSupport = new GenerationRoutingSupport(support);
        AgentGenerationOrchestrator orchestrator = buildOrchestrator(taskStore, support, routingSupport);

        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        writeExistingProjectFile("metrics", CodeGenTypeEnum.HTML, app.getId(), "index.html", "<html><body>login</body></html>");

        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "请补充打包和构建校验",
                CodeGenTypeEnum.HTML,
                "update",
                false,
                null,
                null,
                null
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

        GenerationAgentSupport support = support();
        GenerationRoutingSupport routingSupport = new GenerationRoutingSupport(support);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GenerationOrchestrationMetricsCollector metricsCollector = new GenerationOrchestrationMetricsCollector(meterRegistry);
        GenerationDagRunner dagRunner = new GenerationDagRunner(
                taskStore, metricsCollector, mock(GenerationExecutionContextService.class));
        GenerationRollbackPointService rollbackPointService = testRollbackPointService("metrics");
        AgentGenerationOrchestrator orchestrator = new AgentGenerationOrchestrator(
                dagRunner,
                taskStore,
                new PlannerAgentNode(support, routingSupport),
                testTemplateAgentNode("metrics"),
                new ContextAgentNode(support),
                new ArchitectAgentNode(support),
                codeAgentNode(),
                reviewAgentNode(),
                new BuildFixAgentNode(),
                routingSupport,
                metricsCollector,
                rollbackPointService
        );

        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());

        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "修改登录表单样式",
                CodeGenTypeEnum.HTML,
                "update",
                true,
                null,
                null,
                null
        );

        String orchestrationMode = routingSupport.shouldUseHeavyPath(request) ? "heavy" : "light";
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
                                                          GenerationAgentSupport support,
                                                          GenerationRoutingSupport routingSupport) {
        GenerationOrchestrationMetricsCollector metricsCollector =
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry());
        GenerationDagRunner dagRunner = new GenerationDagRunner(
                taskStore, metricsCollector, mock(GenerationExecutionContextService.class));
        GenerationRollbackPointService rollbackPointService = testRollbackPointService("shared");
        return new AgentGenerationOrchestrator(
                dagRunner,
                taskStore,
                new PlannerAgentNode(support, routingSupport),
                testTemplateAgentNode("shared"),
                new ContextAgentNode(support),
                new ArchitectAgentNode(support),
                codeAgentNode(),
                reviewAgentNode(),
                new BuildFixAgentNode(),
                routingSupport,
                metricsCollector,
                rollbackPointService
        );
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
                mock(GenerationTaskFenceGuard.class)
        );
    }

    private TemplateAgentNode testTemplateAgentNode(String caseName) {
        Path root = Path.of("target", "test-workspaces", "template-orchestrator", caseName);
        FileUtil.del(root.toFile());
        TemplateServiceTestFixture fixture = new TemplateServiceTestFixture(root.resolve("code_output"));
        return new TemplateAgentNode(
                fixture.vueBootstrapService(),
                fixture.backendBootstrapService(),
                new FullStackPortAllocator(fixture.generationWorkspaceService)
        );
    }

    private void writeExistingProjectFile(String caseName,
                                          CodeGenTypeEnum type,
                                          Long appId,
                                          String relativePath,
                                          String content) {
        Path root = Path.of("target", "test-workspaces", "template-orchestrator", caseName,
                "code_output", type.getValue() + "_" + appId);
        FileUtil.writeUtf8String(content, root.resolve(relativePath).toFile());
    }
}
