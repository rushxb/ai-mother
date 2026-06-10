package com.rush.rushaicodemother.orchestration.router;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationModeRouterTest {

    private final GenerationModeRouter router = new GenerationModeRouter();

    @Test
    void shouldRouteMissingWorkspaceToCreate() {
        GenerationModeDecision decision = router.route(
                request("做一个商品管理后台"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(false)
        );

        assertEquals(GenerationMode.CREATE, decision.mode());
        assertEquals(FallbackPolicy.NONE, decision.fallbackPolicy());
    }

    @Test
    void shouldRouteTemplateUncoveredCreateToHeavyExpert() {
        GenerationModeDecision decision = router.route(
                request("做一个高并发微服务支付系统，要求 kubernetes 部署"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(false)
        );

        assertEquals(GenerationMode.HEAVY_EXPERT, decision.mode());
        assertEquals(FallbackPolicy.NONE, decision.fallbackPolicy());
    }

    @Test
    void shouldRouteSmallStyleCopyChangeToLightEdit() {
        GenerationModeDecision decision = router.route(
                request("把首页标题文案改成数据看板，按钮颜色换成蓝色"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(GenerationMode.LIGHT_EDIT, decision.mode());
        assertEquals(ExpectedValidationLevel.FAST, decision.expectedValidationLevel());
    }

    @Test
    void shouldRouteCrossFileApiDatabaseAndBugRequestsToAgentEdit() {
        assertEquals(GenerationMode.AGENT_EDIT, routeExisting("新增用户管理功能，前后端接口和数据库字段都要同步").mode());
        assertEquals(GenerationMode.AGENT_EDIT, routeExisting("修 bug，构建失败提示 api 引用不存在").mode());
    }

    @Test
    void shouldRouteExplicitFullRefactorToHeavyExpert() {
        GenerationModeDecision decision = routeExisting("完整重构整个项目，换框架并全部重写");

        assertEquals(GenerationMode.HEAVY_EXPERT, decision.mode());
        assertEquals(FallbackPolicy.NONE, decision.fallbackPolicy());
    }

    private GenerationModeDecision routeExisting(String message) {
        return router.route(request(message), CodeGenTypeEnum.VUE_PROJECT, workspace(true));
    }

    private GenerationTaskRequest request(String message) {
        App app = new App();
        app.setId(10L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        User user = new User();
        user.setId(20L);
        return new GenerationTaskRequest(app, message, user);
    }

    private GenerationWorkspace workspace(boolean exists) {
        Path rootPath = Path.of("target/test-workspace");
        return new GenerationWorkspace(
                10L,
                CodeGenTypeEnum.VUE_PROJECT,
                rootPath,
                rootPath,
                exists,
                rootPath,
                rootPath,
                Set.of(),
                Set.of()
        );
    }
}
