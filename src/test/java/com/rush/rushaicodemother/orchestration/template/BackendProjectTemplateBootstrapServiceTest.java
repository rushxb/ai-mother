package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackendProjectTemplateBootstrapServiceTest {

    @Test
    void shouldCopyBackendTemplateIntoCanonicalFullStackBackendDirectory() throws Exception {
        Path outputRoot = resetRoot("copy-full-stack");
        BackendProjectTemplateBootstrapService service = new TemplateServiceTestFixture(outputRoot).backendBootstrapService();

        BackendProjectTemplateBootstrapService.BootstrapResult result = service.bootstrapIfNecessary(
                201L,
                CodeGenTypeEnum.FULL_STACK_PROJECT
        );

        Path backendRoot = outputRoot.resolve("full_stack_project_201/backend").toAbsolutePath().normalize();
        assertTrue(result.bootstrapped());
        assertEquals(ProjectTemplateCatalog.GO_SQLITE_BACKEND, result.templateId());
        assertEquals(backendRoot.toString(), result.projectPath());
        assertTrue(Files.isRegularFile(backendRoot.resolve("go.mod")));
    }

    @Test
    void bootstrapFailureMustHideDetailsPreserveCauseAndLeaveNoPublishedWorkspace() throws Exception {
        Path outputRoot = resetRoot("failure");
        PathMatchingResourcePatternResolver resolver = mock(PathMatchingResourcePatternResolver.class);
        IOException failure = new IOException("sensitive-resource-path=C:/internal/templates/backend");
        when(resolver.getResources("classpath:project-templates/go-sqlite-backend-basic/**/*"))
                .thenThrow(failure);
        TemplateServiceTestFixture fixture = new TemplateServiceTestFixture(
                outputRoot,
                resolver,
                new com.rush.rushaicodemother.config.TemplateMaterializationProperties()
        );
        BackendProjectTemplateBootstrapService service = fixture.backendBootstrapService();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.bootstrapIfNecessary(202L, CodeGenTypeEnum.BACKEND_PROJECT)
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertEquals("初始化后端项目模板失败，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("sensitive-resource-path"));
        assertTrue(exception.getCause() instanceof TemplateMaterializationException);
        assertSame(failure, exception.getCause().getCause());
        assertFalse(Files.exists(outputRoot.resolve("backend_project_202")));
    }

    private Path resetRoot(String name) {
        Path root = Path.of("target", "test-workspaces", "template-bootstrap", "backend-" + name);
        FileUtil.del(root.toFile());
        return root;
    }
}