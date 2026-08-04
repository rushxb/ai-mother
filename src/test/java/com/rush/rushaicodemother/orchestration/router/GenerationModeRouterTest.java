package com.rush.rushaicodemother.orchestration.router;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationShadowRoutingMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentProfileService;
import com.rush.rushaicodemother.orchestration.learning.GenerationShadowRoutingProperties;
import com.rush.rushaicodemother.orchestration.learning.GenerationShadowRoutingService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.rush.rushaicodemother.testing.GenerationReleaseSmoke.TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationModeRouterTest {

    private final GenerationModeRouter router = new GenerationModeRouter(
            GenerationRoutingDecisionEngine.defaultEngine()
    );

    @Test
    void selectShouldReturnChampionAndStructuredIntentFromSameRequest() {
        GenerationTaskRequest request = request("颜色改成红色，同时重写登录鉴权和数据库");

        GenerationRouteSelection selection = router.select(
                request,
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(GenerationMode.AGENT_EDIT, selection.decision().mode());
        assertEquals(GenerationRoutingDecisionCode.AGENT_EDIT_COMPLEXITY,
                selection.decision().decisionCode());
        assertTrue(selection.intentProfile().requiresBackend());
        assertTrue(selection.intentProfile().requiresDatabase());
        assertTrue(selection.intentProfile().affectedScopes().contains(IntentAffectedScope.AUTHENTICATION));
        assertEquals(selection.decision().mode(),
                router.route(request, CodeGenTypeEnum.VUE_PROJECT, workspace(true)).mode());
    }

    @Test
    void disabledShadowRoutingShouldNotExecuteChallengerOrChangeChampion() {
        GenerationShadowRoutingProperties properties = new GenerationShadowRoutingProperties();
        AtomicInteger challengerCalls = new AtomicInteger();
        GenerationShadowRoutingService shadowRoutingService = new GenerationShadowRoutingService(
                properties,
                ignored -> {
                    challengerCalls.incrementAndGet();
                    return GenerationModeDecision.of(
                            GenerationMode.HEAVY_EXPERT,
                            0.99,
                            "候选重型路由",
                            FallbackPolicy.NONE,
                            ExpectedValidationLevel.EXPERT,
                            GenerationRoutingDecisionCode.INTENT_PROFILE_HEAVY_EDIT
                    );
                },
                GenerationShadowRoutingMetricsCollector.noOp()
        );
        GenerationModeRouter shadowAwareRouter = new GenerationModeRouter(
                GenerationRoutingDecisionEngine.defaultEngine(),
                (appId, userId) -> GenerationRoutingTelemetrySnapshot.unavailable(),
                new IntentProfileService(),
                shadowRoutingService
        );

        GenerationModeDecision decision = shadowAwareRouter.route(
                request("颜色改成红色，同时重写登录鉴权和数据库"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(GenerationMode.AGENT_EDIT, decision.mode());
        assertEquals(GenerationRoutingDecisionCode.AGENT_EDIT_COMPLEXITY, decision.decisionCode());
        assertEquals(0, challengerCalls.get());
    }

    @Test
    void enabledShadowRoutingShouldExecuteChallengerWithoutReplacingChampion() {
        GenerationShadowRoutingProperties properties = new GenerationShadowRoutingProperties();
        properties.setEnabled(true);
        AtomicInteger challengerCalls = new AtomicInteger();
        GenerationShadowRoutingService shadowRoutingService = new GenerationShadowRoutingService(
                properties,
                ignored -> {
                    challengerCalls.incrementAndGet();
                    return GenerationModeDecision.of(
                            GenerationMode.HEAVY_EXPERT,
                            0.99,
                            "候选重型路由",
                            FallbackPolicy.NONE,
                            ExpectedValidationLevel.EXPERT,
                            GenerationRoutingDecisionCode.INTENT_PROFILE_HEAVY_EDIT
                    );
                },
                GenerationShadowRoutingMetricsCollector.noOp()
        );
        GenerationModeRouter shadowAwareRouter = new GenerationModeRouter(
                GenerationRoutingDecisionEngine.defaultEngine(),
                (appId, userId) -> GenerationRoutingTelemetrySnapshot.unavailable(),
                new IntentProfileService(),
                shadowRoutingService
        );

        GenerationRouteSelection selection = shadowAwareRouter.select(
                request("颜色改成红色，同时重写登录鉴权和数据库"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(GenerationMode.AGENT_EDIT, selection.decision().mode());
        assertEquals(GenerationRoutingDecisionCode.AGENT_EDIT_COMPLEXITY,
                selection.decision().decisionCode());
        assertEquals(1, challengerCalls.get());
    }

    @Test
    @Tag(TAG)
    void shouldRouteMissingWorkspaceToCreate() {
        GenerationModeDecision decision = router.route(
                request("做一个商品管理后台"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(false)
        );

        assertEquals(GenerationMode.CREATE, decision.mode());
        assertEquals(FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT, decision.fallbackPolicy());
        assertChineseMessage(decision.reason());
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
        assertChineseMessage(decision.reason());
    }

    @Test
    @Tag(TAG)
    void shouldRouteSmallStyleCopyChangeToLightEdit() {
        GenerationModeDecision decision = router.route(
                request("把首页标题文案改成数据看板，按钮颜色换成蓝色"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(GenerationMode.LIGHT_EDIT, decision.mode());
        assertEquals(ExpectedValidationLevel.FAST, decision.expectedValidationLevel());
        assertChineseMessage(decision.reason());
    }

    @Test
    @Tag(TAG)
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

    @Test
    void shouldUseChineseReasonForDefaultExistingWorkspaceRoute() {
        GenerationModeDecision decision = routeExisting(
                "请检查现有实现并给出优化方案");

        assertEquals(GenerationMode.AGENT_EDIT, decision.mode());
    }

    @Test
    void invalidRoutingInputsMustUseChineseMessages() {
        BusinessException invalidRequest = assertThrows(BusinessException.class,
                () -> router.route(null, CodeGenTypeEnum.VUE_PROJECT, workspace(true)));
        BusinessException invalidCodeGenType = assertThrows(BusinessException.class,
                () -> router.route(request("修改标题"), null, workspace(true)));
        BusinessException invalidWorkspace = assertThrows(BusinessException.class,
                () -> router.route(request("修改标题"), CodeGenTypeEnum.VUE_PROJECT, null));

        assertChineseMessage(invalidRequest.getMessage());
        assertChineseMessage(invalidCodeGenType.getMessage());
        assertChineseMessage(invalidWorkspace.getMessage());
    }

    @Test
    void customPolicyCanOverrideDefaultRoutingWithoutChangingRouter() {
        GenerationRoutingDecisionEngine engine = new GenerationRoutingDecisionEngine(List.of(signal ->
                java.util.Optional.of(GenerationModeDecision.of(
                        GenerationMode.HEAVY_EXPERT,
                        0.77,
                        "custom production policy",
                        FallbackPolicy.NONE,
                        ExpectedValidationLevel.EXPERT
                ))
        ));
        GenerationModeRouter policyRouter = new GenerationModeRouter(engine);

        GenerationModeDecision decision = policyRouter.route(
                request("title color copy change"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(GenerationMode.HEAVY_EXPERT, decision.mode());
        assertEquals("custom production policy", decision.reason());
    }

    private GenerationModeDecision routeExisting(String message) {
        GenerationModeDecision decision = router.route(
                request(message), CodeGenTypeEnum.VUE_PROJECT, workspace(true));
        assertChineseMessage(decision.reason());
        return decision;
    }

    private void assertChineseMessage(String message) {
        assertTrue(message != null && message.matches(".*[\\u4e00-\\u9fff].*"),
                () -> "用户可见文案必须包含中文: " + message);
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
