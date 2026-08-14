package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.orchestration.pipeline.ReadOnlyGenerationPipeline;
import com.rush.rushaicodemother.orchestration.readonly.ReadOnlyAnalysisModel;
import com.rush.rushaicodemother.orchestration.readonly.ReadOnlyAnalysisService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定只读路径的依赖边界，防止后续迭代重新注入写工具、构建或发布能力。 */
class ReadOnlyGenerationBoundaryArchitectureTest {

    private static final List<String> FORBIDDEN_DEPENDENCY_MARKERS = List.of(
            ".patch.",
            ".tool.",
            ".build.",
            "GenerationWorkspaceReleaseService",
            "GenerationExecutionWorkspaceService",
            "GenerationTaskResourceProvisioningService"
    );

    @Test
    void readOnlyPipelineAndDeepModuleMustNotOwnMutationDependencies() {
        List<String> dependencies = List.of(
                        ReadOnlyGenerationPipeline.class,
                        ReadOnlyAnalysisService.class)
                .stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .map(Class::getName)
                .toList();

        assertTrue(dependencies.stream().noneMatch(this::isForbidden),
                () -> "只读生成边界包含副作用依赖：" + dependencies);
    }

    @Test
    void analysisModelPortMustOnlyExposeDataContracts() {
        Method method = ReadOnlyAnalysisModel.class.getDeclaredMethods()[0];

        assertEquals("analyze", method.getName());
        assertEquals(2, method.getParameterCount());
        assertTrue(Arrays.stream(method.getParameterTypes())
                        .map(Class::getPackageName)
                        .allMatch(packageName -> packageName.equals("java.lang")
                                || packageName.equals(
                                "com.rush.rushaicodemother.orchestration.readonly")),
                "只读模型端口不得暴露工具、工作区或文件系统对象");
        assertEquals("com.rush.rushaicodemother.orchestration.readonly",
                method.getReturnType().getPackageName());
    }

    private boolean isForbidden(String dependencyName) {
        return FORBIDDEN_DEPENDENCY_MARKERS.stream().anyMatch(dependencyName::contains);
    }
}
