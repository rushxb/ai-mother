package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.controller.AiModelController;
import com.rush.rushaicodemother.controller.AppController;
import com.rush.rushaicodemother.controller.GenerationBenchmarkEvidenceController;
import com.rush.rushaicodemother.controller.GenerationTaskController;
import com.rush.rushaicodemother.controller.GenerationTerminalEffectController;
import com.rush.rushaicodemother.controller.app.AppGenerationController;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlAccess;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationControlPlaneRbacArchitectureTest {

    private static final Map<Class<?>, Map<String, GenerationControlPermission>> GUARDED_ENDPOINTS = Map.of(
            GenerationTaskController.class, Map.of(
                    "submit", GenerationControlPermission.TASK_SUBMIT,
                    "get", GenerationControlPermission.TASK_QUERY,
                    "getActiveForApp", GenerationControlPermission.TASK_QUERY,
                    "events", GenerationControlPermission.TASK_QUERY,
                    "cancel", GenerationControlPermission.TASK_CANCEL,
                    "approveToolAction", GenerationControlPermission.TOOL_APPROVAL
            ),
            AppGenerationController.class, Map.of(
                    "startChatToGenCode", GenerationControlPermission.TASK_SUBMIT,
                    "stopChatToGenCode", GenerationControlPermission.TASK_CANCEL,
                    "subscribeChatToGenCode", GenerationControlPermission.TASK_QUERY
            ),
            GenerationTerminalEffectController.class, Map.of(
                    "replayDeadLetter", GenerationControlPermission.TERMINAL_EFFECT_REPLAY
            ),
            GenerationBenchmarkEvidenceController.class, Map.of(
                    "ingest", GenerationControlPermission.BENCHMARK_MANAGE,
                    "get", GenerationControlPermission.BENCHMARK_MANAGE
            ),
            AppController.class, Map.of(
                    "deleteApp", GenerationControlPermission.APP_DELETE,
                    "deleteAppByAdmin", GenerationControlPermission.APP_DELETE
            )
    );

    private static final Set<String> MODEL_CONFIGURATION_ENDPOINTS = Set.of(
            "addModel", "updateModel", "deleteModel", "getModelById",
            "listSupportedModels", "listModelsByPage", "toggleModelEnabled",
            "testModelConnection", "testModelConnectionByConfig"
    );

    @Test
    void tenantAndPlatformEndpointsMustDeclareTheirMatrixPermission() {
        GUARDED_ENDPOINTS.forEach((controller, endpoints) -> endpoints.forEach((methodName, permission) ->
                assertPermission(controller, methodName, permission)));
        MODEL_CONFIGURATION_ENDPOINTS.forEach(methodName ->
                assertPermission(AiModelController.class, methodName,
                        GenerationControlPermission.MODEL_CONFIGURE));
    }

    @Test
    void platformControlEndpointsMustAlsoUseEnforcedAdministratorAspect() {
        guardedMethods().stream()
                .filter(method -> method.getAnnotation(GenerationControlAccess.class)
                        .value().exposure()
                        == GenerationControlPermission.Exposure.PLATFORM_ADMIN_HTTP)
                .forEach(method -> {
                    AuthCheck authCheck = method.getAnnotation(AuthCheck.class);
                    assertNotNull(authCheck, () -> method + " 缺少管理员鉴权切面");
                    assertEquals(UserConstant.ADMIN_ROLE, authCheck.mustRole(),
                            () -> method + " 未要求平台管理员");
                });
    }

    @Test
    void automaticTaskRecoveryMustRemainInternalOnly() {
        assertFalse(GenerationTaskRecoveryService.class.isAnnotationPresent(RestController.class));
        assertTrue(Arrays.stream(GenerationTaskRecoveryService.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(GenerationControlAccess.class)));
        assertEquals(GenerationControlPermission.Exposure.INTERNAL_ONLY,
                GenerationControlPermission.TASK_RECOVERY.exposure());
    }

    @Test
    void everyPublicMatrixPermissionMustBeBoundToAtLeastOneHttpEndpoint() {
        Set<GenerationControlPermission> exposed = guardedMethods().stream()
                .map(method -> method.getAnnotation(GenerationControlAccess.class).value())
                .collect(Collectors.toSet());
        Arrays.stream(GenerationControlPermission.values())
                .filter(permission -> permission.exposure()
                        != GenerationControlPermission.Exposure.INTERNAL_ONLY)
                .forEach(permission -> assertTrue(exposed.contains(permission),
                        () -> "控制权限未绑定 HTTP 入口: " + permission));
    }

    private void assertPermission(Class<?> controller,
                                  String methodName,
                                  GenerationControlPermission expected) {
        Method method = method(controller, methodName);
        GenerationControlAccess access = method.getAnnotation(GenerationControlAccess.class);
        assertNotNull(access, () -> controller.getSimpleName() + "#" + methodName + " 缺少控制权限声明");
        assertEquals(expected, access.value());
    }

    private Set<Method> guardedMethods() {
        return Arrays.stream(new Class<?>[]{
                        GenerationTaskController.class,
                        AppGenerationController.class,
                        GenerationTerminalEffectController.class,
                        GenerationBenchmarkEvidenceController.class,
                        AiModelController.class,
                        AppController.class
                })
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.isAnnotationPresent(GenerationControlAccess.class))
                .collect(Collectors.toSet());
    }

    private Method method(Class<?> type, String methodName) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("接口方法不存在: "
                        + type.getSimpleName() + "#" + methodName));
    }
}
