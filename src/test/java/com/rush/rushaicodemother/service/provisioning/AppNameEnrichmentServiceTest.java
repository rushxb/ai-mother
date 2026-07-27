package com.rush.rushaicodemother.service.provisioning;

import com.rush.rushaicodemother.ai.AppNameGeneratorService;
import com.rush.rushaicodemother.ai.AppNameGeneratorServiceFactory;
import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppNameEnrichmentServiceTest {

    @Test
    void shouldNormalizeAndConditionallyPersistGeneratedName() {
        AppNameGeneratorServiceFactory factory = mock(AppNameGeneratorServiceFactory.class);
        AppNameGeneratorService generator = mock(AppNameGeneratorService.class);
        AppMapper appMapper = mock(AppMapper.class);
        when(factory.createAppNameGeneratorService()).thenReturn(generator);
        when(generator.generateAppName("创建任务管理看板"))
                .thenAnswer(ignored -> {
                    assertEquals("11", MonitorContextHolder.getContext().getAppId());
                    assertEquals("21", MonitorContextHolder.getContext().getUserId());
                    assertEquals("none", MonitorContextHolder.getContext().getTaskId());
                    return "应用名称：‘任务看板’";
                });
        AppNameEnrichmentService service = service(factory, appMapper, Runnable::run);

        service.schedule(11L, 21L, "创建任务管理看板", "创建任务管理看板");

        verify(appMapper).updateGeneratedNameIfUnchanged(
                11L, "创建任务管理看板", "任务看板");
        assertNull(MonitorContextHolder.getContext());
    }

    @Test
    void shouldNotWriteWhenGeneratedNameIsBlankOrUnchanged() {
        AppNameGeneratorServiceFactory factory = mock(AppNameGeneratorServiceFactory.class);
        AppNameGeneratorService generator = mock(AppNameGeneratorService.class);
        AppMapper appMapper = mock(AppMapper.class);
        when(factory.createAppNameGeneratorService()).thenReturn(generator);
        when(generator.generateAppName("同名请求")).thenReturn("同名请求");
        AppNameEnrichmentService service = service(factory, appMapper, Runnable::run);

        service.schedule(12L, 22L, "同名请求", "同名请求");

        verify(appMapper, never()).updateGeneratedNameIfUnchanged(
                anyLong(), anyString(), anyString());
    }

    @Test
    void shouldKeepInitialNameWhenGenerationOrSubmissionFails() {
        AppNameGeneratorServiceFactory factory = mock(AppNameGeneratorServiceFactory.class);
        AppNameGeneratorService generator = mock(AppNameGeneratorService.class);
        AppMapper appMapper = mock(AppMapper.class);
        when(factory.createAppNameGeneratorService()).thenReturn(generator);
        when(generator.generateAppName("模型失败"))
                .thenThrow(new IllegalStateException("model unavailable"));
        AppNameEnrichmentService modelFailureService = service(factory, appMapper, Runnable::run);

        assertDoesNotThrow(() -> modelFailureService.schedule(13L, 23L, "模型失败", "模型失败"));
        assertNull(MonitorContextHolder.getContext());
        verifyNoInteractions(appMapper);

        TaskExecutor rejectingExecutor = task -> {
            throw new IllegalStateException("queue full");
        };
        AppNameEnrichmentService rejectedService = service(factory, appMapper, rejectingExecutor);
        assertDoesNotThrow(() -> rejectedService.schedule(14L, 24L, "队列失败", "队列失败"));
    }

    private AppNameEnrichmentService service(AppNameGeneratorServiceFactory factory,
                                             AppMapper appMapper,
                                             TaskExecutor taskExecutor) {
        return new AppNameEnrichmentService(factory, appMapper, taskExecutor);
    }
}
