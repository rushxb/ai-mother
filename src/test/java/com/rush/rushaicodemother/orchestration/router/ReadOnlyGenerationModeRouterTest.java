package com.rush.rushaicodemother.orchestration.router;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadOnlyGenerationModeRouterTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "解释一下当前鉴权链路，不要修改代码",
            "审计当前鉴权链路的安全风险，不要修改代码",
            "先给出数据库迁移方案，不要实现"
    })
    void productionRouterMustSendReadOnlyOperationsToReadOnlyPipeline(String prompt) {
        GenerationModeRouter router = new GenerationModeRouter(
                GenerationRoutingDecisionEngine.defaultEngine());

        GenerationModeDecision decision = router.route(
                request(prompt), CodeGenTypeEnum.VUE_PROJECT, workspace());

        assertEquals(GenerationMode.READ_ONLY, decision.mode());
        assertEquals(GenerationRoute.READ_ONLY, decision.route());
        assertEquals(FallbackPolicy.NONE, decision.fallbackPolicy());
        assertEquals(ExpectedValidationLevel.FAST, decision.expectedValidationLevel());
        assertEquals(GenerationRoutingDecisionCode.INTENT_PROFILE_READ_ONLY,
                decision.decisionCode());
    }

    private GenerationTaskRequest request(String prompt) {
        App app = App.builder().id(10L).userId(20L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue()).build();
        User user = User.builder().id(20L).build();
        return new GenerationTaskRequest(app, prompt, user);
    }

    private GenerationWorkspace workspace() {
        Path root = Path.of("target/read-only-router-test").toAbsolutePath().normalize();
        return new GenerationWorkspace(
                10L, CodeGenTypeEnum.VUE_PROJECT, root, root, true,
                root, root, Set.of(), Set.of());
    }
}
