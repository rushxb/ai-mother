package com.rush.rushaicodemother.orchestration.review;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendQualityReviewServiceTest {

    private final BackendQualityReviewService service = new BackendQualityReviewService();

    @Test
    void malformedApiContractArtifactMustFailClosed() {
        GenerationAgentContext context = backendContext();
        context.putArtifacts(List.of(GenerationArtifact.of(
                "api_contract",
                "Planner",
                "API 字段契约",
                Map.of("status", "ready")
        )));

        BackendQualityReviewService.BackendReviewResult result = service.review(
                context,
                "SQLite Repository 参数化 SQL internal/modules internal/domain"
        );

        assertFalse(result.passed());
        assertTrue(result.blockers().stream()
                .anyMatch(message -> message.contains("API 字段契约")));
    }

    private GenerationAgentContext backendContext() {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.BACKEND_PROJECT.getValue());
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "生成商品管理后端",
                CodeGenTypeEnum.BACKEND_PROJECT,
                "create",
                false,
                null,
                null
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("api-contract-review-test");
        return new GenerationAgentContext(request, task, true);
    }
}
