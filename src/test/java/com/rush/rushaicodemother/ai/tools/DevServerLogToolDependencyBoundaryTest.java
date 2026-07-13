package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.devserver.DevServerAppTargetLookup;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class DevServerLogToolDependencyBoundaryTest {

    @Test
    void toolMustNotDependOnApplicationOrchestrationService() {
        boolean dependsOnAppService = Arrays.stream(DevServerLogTool.class.getDeclaredConstructors())
                .map(Constructor::getParameterTypes)
                .flatMap(Arrays::stream)
                .anyMatch(AppService.class::isAssignableFrom);

        assertFalse(
                dependsOnAppService,
                "DevServerLogTool 不能依赖 AppService，否则会反向连接应用生成编排并形成 Spring Bean 循环"
        );
    }

    @Test
    void toolMustWireWithoutApplicationOrchestrationService() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AppMapper.class, () -> mock(AppMapper.class));
            context.registerBean(DevServerManager.class, () -> mock(DevServerManager.class));
            context.register(DevServerAppTargetLookup.class, DevServerLogTool.class);

            context.refresh();

            assertNotNull(context.getBean(DevServerLogTool.class));
        }
    }
}
