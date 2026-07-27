package com.rush.rushaicodemother.ai.intent;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicCodeGenTypeRouterTest {

    private final DeterministicCodeGenTypeRouter router = new DeterministicCodeGenTypeRouter();

    @Test
    void shouldRouteExplicitBackendAndFullstackWithoutAi() {
        assertEquals(
                Optional.of(CodeGenTypeEnum.BACKEND_PROJECT),
                router.route("创建 Go 后端接口", BackendIntentDetector.BackendIntentResult.explicitBackend())
        );
        assertEquals(
                Optional.of(CodeGenTypeEnum.FULL_STACK_PROJECT),
                router.route("创建前后端商城", BackendIntentDetector.BackendIntentResult.fullstack())
        );
    }

    @Test
    void shouldRouteClearFrontendShapesWithoutAi() {
        assertEquals(
                Optional.of(CodeGenTypeEnum.HTML),
                router.route("构建一个简单的个人介绍页面", BackendIntentDetector.BackendIntentResult.none())
        );
        assertEquals(
                Optional.of(CodeGenTypeEnum.MULTI_FILE),
                router.route("使用 HTML/CSS/JS 分离实现静态网站", BackendIntentDetector.BackendIntentResult.none())
        );
        assertEquals(
                Optional.of(CodeGenTypeEnum.VUE_PROJECT),
                router.route("做一个带路由和状态管理的后台", BackendIntentDetector.BackendIntentResult.none())
        );
    }

    @Test
    void shouldDefaultOrdinaryFrontendRequestToVue() {
        assertEquals(
                Optional.of(CodeGenTypeEnum.VUE_PROJECT),
                router.route("做一个在线商城", BackendIntentDetector.BackendIntentResult.none())
        );
    }

    @Test
    void explicitRouteMustNotApplyTheNewApplicationDefault() {
        assertTrue(router.routeExplicit(
                "继续优化当前项目",
                BackendIntentDetector.BackendIntentResult.none()
        ).isEmpty());
        assertEquals(
                Optional.of(CodeGenTypeEnum.VUE_PROJECT),
                router.routeExplicit(
                        "升级为 Vue 工程",
                        BackendIntentDetector.BackendIntentResult.none()
                )
        );
    }

    @Test
    void shouldRequireAiOnlyForAmbiguousIntent() {
        assertTrue(router.route(
                "创建 Vue 页面并连接 API",
                BackendIntentDetector.BackendIntentResult.ambiguous()
        ).isEmpty());
    }
}
