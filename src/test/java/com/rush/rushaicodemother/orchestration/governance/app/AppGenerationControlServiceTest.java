package com.rush.rushaicodemother.orchestration.governance.app;

import com.rush.rushaicodemother.application.app.AppAccessPolicy;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskControlService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppGenerationControlServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-28T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void firstUpdateMustCreateVersionOneAndTriggerEmergencyCancellation() {
        AppGenerationControlRepository repository = mock(AppGenerationControlRepository.class);
        AppAccessPolicy accessPolicy = mock(AppAccessPolicy.class);
        GenerationTaskControlService taskControlService = mock(GenerationTaskControlService.class);
        App app = App.builder().id(11L).tenantId(100L).build();
        User actor = User.builder().id(7L).build();
        when(repository.lockActiveApplication(11L)).thenReturn(app);
        when(repository.get(11L)).thenReturn(AppGenerationControlPolicy.defaults(11L));
        when(repository.insert(any())).thenReturn(true);
        AppGenerationControlService service = new AppGenerationControlService(
                repository, accessPolicy, taskControlService, CLOCK);

        AppGenerationControlPolicy updated = service.update(11L, command(0L, true), actor);

        assertEquals(1L, updated.version());
        assertEquals(7L, updated.updatedBy());
        assertEquals(Instant.parse("2026-08-28T08:00:00Z"), updated.updatedAt());
        verify(accessPolicy).requireOwnerOrAdmin(eq(app), eq(actor), any());
        verify(repository).insert(updated);
        verify(taskControlService).emergencyStopActiveForApp(11L);
    }

    @Test
    void staleVersionMustFailWithoutMutationOrCancellation() {
        AppGenerationControlRepository repository = mock(AppGenerationControlRepository.class);
        AppAccessPolicy accessPolicy = mock(AppAccessPolicy.class);
        GenerationTaskControlService taskControlService = mock(GenerationTaskControlService.class);
        App app = App.builder().id(11L).tenantId(100L).build();
        User actor = User.builder().id(7L).build();
        when(repository.lockActiveApplication(11L)).thenReturn(app);
        when(repository.get(11L)).thenReturn(persistedPolicy(2L));
        AppGenerationControlService service = new AppGenerationControlService(
                repository, accessPolicy, taskControlService, CLOCK);

        assertThrows(BusinessException.class,
                () -> service.update(11L, command(1L, true), actor));

        verify(repository, never()).insert(any());
        verify(repository, never()).update(any(), any(Long.class));
        verify(taskControlService, never()).emergencyStopActiveForApp(any());
    }

    private AppGenerationControlUpdateCommand command(long expectedVersion,
                                                       boolean emergencyStopped) {
        return new AppGenerationControlUpdateCommand(
                expectedVersion,
                false,
                emergencyStopped,
                1,
                AppGenerationControlPolicy.ModelPolicy.ECONOMY_ONLY,
                AppGenerationControlPolicy.DependencyMutationPolicy.DENY,
                AppGenerationControlPolicy.DependencyNetworkPolicy.DENY,
                AppGenerationControlPolicy.DangerousToolPolicy.DENY,
                50L
        );
    }

    private AppGenerationControlPolicy persistedPolicy(long version) {
        return new AppGenerationControlPolicy(
                11L, version, false, false, 1,
                AppGenerationControlPolicy.ModelPolicy.PLATFORM_DEFAULT,
                AppGenerationControlPolicy.DependencyMutationPolicy.ALLOW,
                AppGenerationControlPolicy.DependencyNetworkPolicy.TRUSTED_REGISTRY_ONLY,
                AppGenerationControlPolicy.DangerousToolPolicy.REQUIRE_APPROVAL,
                null, 9L, Instant.parse("2026-08-28T07:00:00Z"));
    }
}
