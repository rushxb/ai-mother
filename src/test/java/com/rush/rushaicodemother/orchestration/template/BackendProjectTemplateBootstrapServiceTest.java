package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackendProjectTemplateBootstrapServiceTest {

    @Test
    void bootstrapFailureMustHideInternalDetailsPreserveCauseAndRemovePartialWorkspace() throws Exception {
        Path outputRoot = Path.of("target", "test-workspaces", "template-bootstrap", "backend-failure");
        FileUtil.del(outputRoot.toFile());
        PathMatchingResourcePatternResolver resolver = mock(PathMatchingResourcePatternResolver.class);
        IOException failure = new IOException("sensitive-resource-path=C:/internal/templates/backend");
        when(resolver.getResources("classpath:project-templates/go-sqlite-backend-basic/**/*"))
                .thenThrow(failure);
        BackendProjectTemplateBootstrapService service = new BackendProjectTemplateBootstrapService(
                outputRoot,
                resolver
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.bootstrapIfNecessary(201L)
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertEquals("初始化后端项目模板失败，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("sensitive-resource-path"));
        assertSame(failure, exception.getCause());
        assertFalse(Files.exists(outputRoot.resolve("backend_project_201")));
    }
}
