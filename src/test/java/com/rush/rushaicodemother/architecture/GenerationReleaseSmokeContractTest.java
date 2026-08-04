package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static com.rush.rushaicodemother.testing.GenerationReleaseSmoke.TAG;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TAG)
class GenerationReleaseSmokeContractTest {

    private static final String ROUTER_TEST =
            "com.rush.rushaicodemother.orchestration.router.GenerationModeRouterTest";
    private static final String CONTROLLER_TEST =
            "com.rush.rushaicodemother.controller.GenerationTaskControllerTest";
    private static final String SUBMISSION_TEST =
            "com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionServiceTest";

    private static final List<SmokeScenario> REQUIRED_SCENARIOS = List.of(
            new SmokeScenario("创建", ROUTER_TEST, "shouldRouteMissingWorkspaceToCreate"),
            new SmokeScenario("轻量编辑", ROUTER_TEST, "shouldRouteSmallStyleCopyChangeToLightEdit"),
            new SmokeScenario("复杂编辑", ROUTER_TEST,
                    "shouldRouteCrossFileApiDatabaseAndBugRequestsToAgentEdit"),
            new SmokeScenario("取消", CONTROLLER_TEST,
                    "getAndCancelMustDelegateThroughTaskScopedAuthorizationServices"),
            new SmokeScenario("审批", CONTROLLER_TEST,
                    "destructiveToolApprovalMustBeTaskScopedAndOwnershipChecked"),
            new SmokeScenario("幂等", SUBMISSION_TEST,
                    "idempotentReplayMustReturnOriginalTaskWithoutDispatchOrCompensation"),
            new SmokeScenario("SSE 续传", CONTROLLER_TEST,
                    "eventsMustResumeFromNewestCursorAndExposeSequencedSseIds")
    );

    @Test
    void allRequiredGenerationReleaseScenariosMustRemainTagged()
            throws ClassNotFoundException, NoSuchMethodException {
        for (SmokeScenario scenario : REQUIRED_SCENARIOS) {
            Class<?> testClass = Class.forName(scenario.testClassName());
            Method method = testClass.getDeclaredMethod(scenario.methodName());
            boolean tagged = Arrays.stream(method.getAnnotationsByType(Tag.class))
                    .anyMatch(tag -> TAG.equals(tag.value()));
            assertTrue(tagged, () -> scenario.name() + "场景缺少发布冒烟标签: "
                    + testClass.getSimpleName() + "#" + scenario.methodName());
        }
    }

    private record SmokeScenario(String name, String testClassName, String methodName) {
    }
}