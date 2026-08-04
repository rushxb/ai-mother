package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskOrchestrator;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskIdempotency;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppServiceImplGenerationSubmissionTest {

    @Test
    void shouldReplaySubmissionWithoutChangingDatabaseResourceOnRequestThread() {
        AppServiceImplTestFixture fixture = new AppServiceImplTestFixture();
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        AppServiceImpl service = fixture.withGenerationTaskOrchestrator(orchestrator).createService();

        App app = new App();
        app.setId(10L);
        app.setTenantId(100L);
        app.setUserId(20L);
        app.setCodeGenType(CodeGenTypeEnum.FULL_STACK_PROJECT.getValue());
        User user = new User();
        user.setId(20L);
        String prompt = "请新增用户数据库和登录接口";
        GenerationTaskIdempotency idempotency = GenerationTaskIdempotency.none();
        GenerationTaskResult expectedResult = mock(GenerationTaskResult.class);

        when(fixture.persistenceService().findActiveById(app.getId())).thenReturn(app);
        when(fixture.databaseResourceService().shouldEnableForPrompt(prompt)).thenReturn(true);
        when(fixture.idempotencyService().resolve("request-key", app.getId(), prompt))
                .thenReturn(idempotency);
        when(orchestrator.start(org.mockito.ArgumentMatchers.any(GenerationTaskRequest.class),
                eq(idempotency))).thenReturn(expectedResult);

        GenerationTaskResult firstResult =
                service.submitGeneration(app.getId(), prompt, user, "request-key");
        GenerationTaskResult replayResult =
                service.submitGeneration(app.getId(), prompt, user, "request-key");

        assertSame(expectedResult, firstResult);
        assertSame(expectedResult, replayResult);
        ArgumentCaptor<GenerationTaskRequest> requestCaptor =
                ArgumentCaptor.forClass(GenerationTaskRequest.class);
        verify(orchestrator, times(2)).start(requestCaptor.capture(), eq(idempotency));
        assertTrue(requestCaptor.getAllValues().stream()
                .allMatch(request -> request.resourceRequirements().databaseRequired()));
        verify(fixture.databaseResourceService(), never()).enableDatabase(app);
    }
}

