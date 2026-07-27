package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import org.junit.jupiter.api.Test;

import static com.rush.rushaicodemother.orchestration.agent.GenerationAgentTestFixture.support;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRoutingSupportTest {

    private final GenerationAgentSupport generationAgentSupport = support();
    private final GenerationRoutingSupport routingSupport = new GenerationRoutingSupport(generationAgentSupport);

    @Test
    void shouldEnableHeavyPathForBuildIntensiveHtmlRequest() {
        GenerationOrchestrationRequest request = request(
                CodeGenTypeEnum.HTML,
                "请补充打包和构建校验",
                false
        );

        assertTrue(routingSupport.shouldUseHeavyPath(request));
        assertTrue(routingSupport.requiresBuildValidation(request, routingSupport.routeTargetType(request)));
    }

    @Test
    void shouldRouteToVueForComplexRouting() {
        GenerationOrchestrationRequest request = request(
                CodeGenTypeEnum.HTML,
                "创建一个 Vue 后台管理面板",
                false
        );

        assertEquals(CodeGenTypeEnum.VUE_PROJECT, routingSupport.routeTargetType(request));
        assertFalse(routingSupport.shouldUseHeavyPath(request));
    }

    @Test
    void backendProjectsMustAlwaysRequireTheGoBuildGate() {
        GenerationOrchestrationRequest request = request(
                CodeGenTypeEnum.HTML,
                "创建一个简单的 Go 后端 API",
                false
        );

        assertEquals(CodeGenTypeEnum.BACKEND_PROJECT, routingSupport.routeTargetType(request));
        assertTrue(routingSupport.requiresBuildValidation(request, CodeGenTypeEnum.BACKEND_PROJECT));
    }

    private GenerationOrchestrationRequest request(CodeGenTypeEnum currentType,
                                                   String userMessage,
                                                   boolean hasGeneratedCode) {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(currentType.getValue());
        return new GenerationOrchestrationRequest(
                app,
                userMessage,
                currentType,
                "update",
                hasGeneratedCode,
                prompt -> CodeGenTypeEnum.VUE_PROJECT,
                null
        );
    }
}
