package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.verification.runtime.BackendRuntimeValidationResult;
import com.rush.rushaicodemother.orchestration.verification.runtime.ProjectRuntimeValidationResult;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationProjectRuntimeValidationServiceTest {

    @Test
    void registeredAdapterExtendsRuntimeValidationWithoutChangingDispatcher() {
        ProjectRuntimeValidationResult expected = ProjectRuntimeValidationResult.fromBackend(
                BackendRuntimeValidationResult.failed(8, "html_runtime_probe"));
        GenerationProjectRuntimeValidationAdapter htmlAdapter =
                new GenerationProjectRuntimeValidationAdapter() {
                    @Override
                    public CodeGenTypeEnum codeGenType() {
                        return CodeGenTypeEnum.HTML;
                    }

                    @Override
                    public ProjectRuntimeValidationResult validateRuntime(
                            GenerationProjectRuntimeValidationRequest request
                    ) {
                        return expected;
                    }
                };
        GenerationProjectRuntimeValidationService service =
                new GenerationProjectRuntimeValidationService(List.of(htmlAdapter));

        ProjectRuntimeValidationResult actual = service.validate(request(CodeGenTypeEnum.HTML));

        assertEquals(expected, actual);
    }

    @Test
    void duplicateRuntimeAdaptersMustFailDuringRegistryConstruction() {
        GenerationProjectRuntimeValidationAdapter first = adapter(
                CodeGenTypeEnum.HTML,
                ProjectRuntimeValidationResult.fromBackend(
                        BackendRuntimeValidationResult.failed(1, "first")));
        GenerationProjectRuntimeValidationAdapter second = adapter(
                CodeGenTypeEnum.HTML,
                ProjectRuntimeValidationResult.fromBackend(
                        BackendRuntimeValidationResult.failed(1, "second")));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new GenerationProjectRuntimeValidationService(List.of(first, second)));

        assertTrue(exception.getMessage().contains("重复运行时验证适配器"));
    }

    @Test
    void missingRuntimeAdapterMustFailWithExplicitProjectType() {
        GenerationProjectRuntimeValidationService service =
                new GenerationProjectRuntimeValidationService(List.of(adapter(
                        CodeGenTypeEnum.HTML,
                        ProjectRuntimeValidationResult.fromBackend(
                                BackendRuntimeValidationResult.failed(1, "html")))));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.validate(request(CodeGenTypeEnum.VUE_PROJECT)));

        assertTrue(exception.getMessage().contains(CodeGenTypeEnum.VUE_PROJECT.getValue()));
    }

    @Test
    void runtimeAdapterMustNotReturnNullResult() {
        GenerationProjectRuntimeValidationAdapter invalidAdapter =
                adapter(CodeGenTypeEnum.HTML, null);
        GenerationProjectRuntimeValidationService service =
                new GenerationProjectRuntimeValidationService(List.of(invalidAdapter));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.validate(request(CodeGenTypeEnum.HTML)));

        assertTrue(exception.getMessage().contains("返回空结果"));
    }

    private GenerationProjectRuntimeValidationAdapter adapter(
            CodeGenTypeEnum codeGenType,
            ProjectRuntimeValidationResult result
    ) {
        return new GenerationProjectRuntimeValidationAdapter() {
            @Override
            public CodeGenTypeEnum codeGenType() {
                return codeGenType;
            }

            @Override
            public ProjectRuntimeValidationResult validateRuntime(
                    GenerationProjectRuntimeValidationRequest request
            ) {
                return result;
            }
        };
    }

    private GenerationProjectRuntimeValidationRequest request(CodeGenTypeEnum codeGenType) {
        Path root = Path.of("build", "runtime-validation", codeGenType.getValue());
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L,
                codeGenType,
                root,
                root,
                true,
                root,
                root,
                Set.of(),
                Set.of()
        );
        return new GenerationProjectRuntimeValidationRequest(
                "runtime-validation-test",
                1L,
                2L,
                workspace,
                null,
                null,
                () -> false,
                () -> { }
        );
    }
}
