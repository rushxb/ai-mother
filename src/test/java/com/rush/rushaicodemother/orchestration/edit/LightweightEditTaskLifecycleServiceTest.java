package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LightweightEditTaskLifecycleServiceTest {

    private final GenerationTaskLifecycleService taskLifecycleService =
            mock(GenerationTaskLifecycleService.class);
    private final LightweightEditTaskLifecycleService lifecycleService =
            new LightweightEditTaskLifecycleService(taskLifecycleService);

    @Test
    void startMustRecordMessageAndAtomicallyStartTraceAndApplicationState() {
        App app = app(11L);
        User user = user(22L);

        lifecycleService.start(
                "edit_task", app, user, CodeGenTypeEnum.VUE_PROJECT, "repair page", true);

        verify(taskLifecycleService).recordUserMessage(app, user, "repair page");
        verify(taskLifecycleService).startGeneration(
                "edit_task", app, user,
                CodeGenTypeEnum.VUE_PROJECT, CodeGenTypeEnum.VUE_PROJECT,
                "repair page", "repair page", true, "lightweight",
                "lightweight_edit", AppConstant.GENERATING_STAGE_UPDATE);
    }

    @Test
    void completeSuccessMustAtomicallyFinishStateTraceAndCharge() {
        lifecycleService.completeSuccess("edit_task", 11L);

        verify(taskLifecycleService).completeGenerationAndCharge(
                "edit_task", 11L, GenerationTaskStatus.SUCCESS, null);
    }

    @Test
    void completeFailureMustDelegateToAtomicLifecycleBoundary() {
        IllegalStateException failure = new IllegalStateException("database_unavailable");
        doThrow(failure).when(taskLifecycleService).completeGeneration(
                "edit_task", 11L, GenerationTaskStatus.FAILED, "validation_failed");

        assertThatThrownBy(() -> lifecycleService.completeFailure(
                "edit_task", 11L, "validation_failed"))
                .isSameAs(failure);
    }

    @Test
    void failedAtomicStartMustNotRunLegacyCompensationWrites() {
        App app = app(11L);
        User user = user(22L);
        IllegalStateException startFailure = new IllegalStateException("database_unavailable");
        doThrow(startFailure).when(taskLifecycleService).startGeneration(
                "edit_task", app, user,
                CodeGenTypeEnum.VUE_PROJECT, CodeGenTypeEnum.VUE_PROJECT,
                "repair page", "repair page", false, "lightweight",
                "lightweight_edit", AppConstant.GENERATING_STAGE_UPDATE);

        assertThatThrownBy(() -> lifecycleService.start(
                "edit_task", app, user, CodeGenTypeEnum.VUE_PROJECT, "repair page", false))
                .isSameAs(startFailure);

        verify(taskLifecycleService, never()).completeGeneration(
                "edit_task", 11L, GenerationTaskStatus.FAILED, "lightweight_edit_start_failed");
    }

    private App app(Long appId) {
        App app = new App();
        app.setId(appId);
        return app;
    }

    private User user(Long userId) {
        User user = new User();
        user.setId(userId);
        return user;
    }
}
