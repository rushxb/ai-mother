package com.yupi.yuaicodemother.orchestration;

import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.agent.ArchitectAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.BuildFixAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.CodeAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.ContextAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.GenerationAgentSupport;
import com.yupi.yuaicodemother.orchestration.agent.GenerationRoutingSupport;
import com.yupi.yuaicodemother.orchestration.agent.PlannerAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.ReviewAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.TemplateAgentNode;
import com.yupi.yuaicodemother.orchestration.dag.GenerationDagRunner;
import com.yupi.yuaicodemother.orchestration.dag.GenerationOrchestrationTask;
import com.yupi.yuaicodemother.orchestration.dag.GenerationOrchestrationTaskStore;
import com.yupi.yuaicodemother.orchestration.snapshot.GenerationRollbackPointService;
import com.yupi.yuaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.yupi.yuaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentGenerationOrchestratorTest {

    @Test
    void shouldRouteToHeavyPathWhenTargetTypeUpgradesToVue() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-heavy");
        when(taskStore.create(anyLong(), anyString())).thenReturn(task);

        GenerationAgentSupport support = new GenerationAgentSupport();
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
                routingFunction
        );

        GenerationOrchestrationResult result = orchestrator.prepare(request);

        assertEquals(CodeGenTypeEnum.VUE_PROJECT, result.targetType());
        assertTrue(result.artifacts().containsKey("buildfix_plan"));
        assertTrue(result.artifacts().containsKey("rollback_point"));
        GenerationArtifact rollbackPoint = result.artifacts().get("rollback_point");
        assertEquals("skipped", rollbackPoint.payload().get("status"));
        assertEquals("no_existing_generated_code", rollbackPoint.payload().get("reason"));
        @SuppressWarnings("unchecked")
        Map<String, Object> buildfixPlan = result.artifacts().get("buildfix_plan").payload();
        assertEquals(Boolean.TRUE, buildfixPlan.get("enabled"));
    }

    @Test
    void shouldRouteToHeavyPathForExistingProjectVueUpgrade() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-upgrade");
        when(taskStore.create(anyLong(), anyString())).thenReturn(task);

        GenerationAgentSupport support = new GenerationAgentSupport();
        GenerationRoutingSupport routingSupport = new GenerationRoutingSupport(support);
        AgentGenerationOrchestrator orchestrator = buildOrchestrator(taskStore, support, routingSupport);

        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.MULTI_FILE.getValue());

        Function<String, CodeGenTypeEnum> routingFunction = prompt -> CodeGenTypeEnum.VUE_PROJECT;
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "做一个工具页",
                CodeGenTypeEnum.MULTI_FILE,
                "update",
                true,
                null,
                routingFunction
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

        GenerationAgentSupport support = new GenerationAgentSupport();
        GenerationRoutingSupport routingSupport = new GenerationRoutingSupport(support);
        AgentGenerationOrchestrator orchestrator = buildOrchestrator(taskStore, support, routingSupport);

        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());

        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "请补充打包和构建校验",
                CodeGenTypeEnum.HTML,
                "update",
                false,
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

        GenerationAgentSupport support = new GenerationAgentSupport();
        GenerationRoutingSupport routingSupport = new GenerationRoutingSupport(support);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GenerationOrchestrationMetricsCollector metricsCollector = new GenerationOrchestrationMetricsCollector(meterRegistry);
        GenerationDagRunner dagRunner = new GenerationDagRunner(taskStore, metricsCollector);
        GenerationRollbackPointService rollbackPointService = testRollbackPointService("metrics");
        AgentGenerationOrchestrator orchestrator = new AgentGenerationOrchestrator(
                dagRunner,
                taskStore,
                new PlannerAgentNode(support, routingSupport),
                testTemplateAgentNode("metrics"),
                new ContextAgentNode(support),
                new ArchitectAgentNode(support),
                new CodeAgentNode(),
                new ReviewAgentNode(),
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
        GenerationDagRunner dagRunner = new GenerationDagRunner(taskStore, metricsCollector);
        GenerationRollbackPointService rollbackPointService = testRollbackPointService("shared");
        return new AgentGenerationOrchestrator(
                dagRunner,
                taskStore,
                new PlannerAgentNode(support, routingSupport),
                testTemplateAgentNode("shared"),
                new ContextAgentNode(support),
                new ArchitectAgentNode(support),
                new CodeAgentNode(),
                new ReviewAgentNode(),
                new BuildFixAgentNode(),
                routingSupport,
                metricsCollector,
                rollbackPointService
        );
    }

    private GenerationRollbackPointService testRollbackPointService(String caseName) {
        Path root = Path.of("target", "test-workspaces", "rollback-orchestrator", caseName);
        FileUtil.del(root.toFile());
        return new GenerationRollbackPointService(root.resolve("code_output"), root.resolve("code_snapshot"));
    }

    private TemplateAgentNode testTemplateAgentNode(String caseName) {
        Path root = Path.of("target", "test-workspaces", "template-orchestrator", caseName);
        FileUtil.del(root.toFile());
        VueProjectTemplateBootstrapService bootstrapService = new VueProjectTemplateBootstrapService(
                root.resolve("code_output"),
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
        );
        BackendProjectTemplateBootstrapService backendBootstrapService = new BackendProjectTemplateBootstrapService(
                root.resolve("code_output"),
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
        );
        return new TemplateAgentNode(bootstrapService, backendBootstrapService);
    }
}
