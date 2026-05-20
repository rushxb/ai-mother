package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRoutingSupportTest {

    private final GenerationAgentSupport generationAgentSupport = new GenerationAgentSupport();
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
                null,
                prompt -> CodeGenTypeEnum.VUE_PROJECT,
                null
        );
    }
}
