package com.yupi.yuaicodemother.orchestration;

import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.agent.ArchitectAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.BuildFixAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.CodeAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.ContextAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.GenerationAgentSupport;
import com.yupi.yuaicodemother.orchestration.agent.PlannerAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.ReviewAgentNode;
import com.yupi.yuaicodemother.orchestration.dag.GenerationDagRunner;
import com.yupi.yuaicodemother.orchestration.dag.GenerationOrchestrationTask;
import com.yupi.yuaicodemother.orchestration.dag.GenerationOrchestrationTaskStore;
import org.junit.jupiter.api.Test;

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

        GenerationDagRunner dagRunner = new GenerationDagRunner(taskStore);
        GenerationAgentSupport support = new GenerationAgentSupport();
        AgentGenerationOrchestrator orchestrator = new AgentGenerationOrchestrator(
                dagRunner,
                taskStore,
                new PlannerAgentNode(support),
                new ContextAgentNode(support),
                new ArchitectAgentNode(support),
                new CodeAgentNode(),
                new ReviewAgentNode(),
                new BuildFixAgentNode()
        );

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

        GenerationDagRunner dagRunner = new GenerationDagRunner(taskStore);
        GenerationAgentSupport support = new GenerationAgentSupport();
        AgentGenerationOrchestrator orchestrator = new AgentGenerationOrchestrator(
                dagRunner,
                taskStore,
                new PlannerAgentNode(support),
                new ContextAgentNode(support),
                new ArchitectAgentNode(support),
                new CodeAgentNode(),
                new ReviewAgentNode(),
                new BuildFixAgentNode()
        );

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
}
