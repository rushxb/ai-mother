package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationAppStateServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-14T06:00:00Z"), ZoneOffset.UTC);

    private AppMapper appMapper;
    private GenerationRuntimeProperties runtimeProperties;
    private GenerationAppStateService stateService;

    @BeforeEach
    void setUp() {
        appMapper = mock(AppMapper.class);
        runtimeProperties = new GenerationRuntimeProperties();
        runtimeProperties.setTaskTimeout(Duration.ofMinutes(20));
        stateService = new GenerationAppStateService(appMapper, runtimeProperties, FIXED_CLOCK);
    }

    @Test
    void claimMustPersistTaskOwnerTargetTypeAndBoundedLease() {
        when(appMapper.claimGenerationState(any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(1);
        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> leaseCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        stateService.claimGenerationState(11L, "task-1", "create", CodeGenTypeEnum.VUE_PROJECT);

        verify(appMapper).claimGenerationState(
                eq(11L), eq("task-1"), eq(0L), eq("create"), eq("vue_project"),
                nowCaptor.capture(), leaseCaptor.capture());
        assertEquals(LocalDateTime.of(2026, 7, 14, 6, 0), nowCaptor.getValue());
        assertEquals(Duration.ofMinutes(21),
                Duration.between(nowCaptor.getValue(), leaseCaptor.getValue()));
        verify(appMapper, never()).selectGenerationState(11L);
    }

    @Test
    void claimMustDifferentiateMissingApplicationFromConcurrentOwner() {
        when(appMapper.claimGenerationState(any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(0);

        BusinessException missing = assertThrows(
                BusinessException.class,
                () -> stateService.claimGenerationState(
                        11L, "task-1", "create", CodeGenTypeEnum.VUE_PROJECT));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), missing.getCode());

        App busyState = new App();
        busyState.setId(11L);
        busyState.setIsGenerating(1);
        busyState.setGeneratingTaskId("task-other");
        when(appMapper.selectGenerationState(11L)).thenReturn(busyState);

        BusinessException busy = assertThrows(
                BusinessException.class,
                () -> stateService.claimGenerationState(
                        11L, "task-1", "create", CodeGenTypeEnum.VUE_PROJECT));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), busy.getCode());
    }

    @Test
    void ownedStageAndSnapshotWritesMustRejectLostOwnership() {
        App currentState = new App();
        currentState.setId(11L);
        currentState.setGeneratingTaskId("task-other");
        when(appMapper.selectGenerationState(11L)).thenReturn(currentState);

        BusinessException stageFailure = assertThrows(
                BusinessException.class,
                () -> stateService.updateOwnedGenerationStage(
                        11L, "task-1", "build", "building"));
        BusinessException snapshotFailure = assertThrows(
                BusinessException.class,
                () -> stateService.updateOwnedGenerationSnapshot(
                        11L, "task-1", "partial output"));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), stageFailure.getCode());
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), snapshotFailure.getCode());
    }

    @Test
    void releaseMustBeOwnerScopedAndIdempotent() {
        when(appMapper.releaseOwnedGenerationState(11L, "task-1", 0L))
                .thenReturn(1)
                .thenReturn(0);

        assertTrue(stateService.releaseOwnedGenerationState(11L, "task-1"));
        assertFalse(stateService.releaseOwnedGenerationState(11L, "task-1"));
    }

    @Test
    void invalidIdentifiersMustNotReachMapper() {
        assertThrows(BusinessException.class, () -> stateService.claimGenerationState(
                null, "task-1", "create", CodeGenTypeEnum.VUE_PROJECT));
        assertThrows(BusinessException.class, () -> stateService.claimGenerationState(
                11L, " ", "create", CodeGenTypeEnum.VUE_PROJECT));
        assertThrows(BusinessException.class, () -> stateService.updateOwnedGenerationStage(
                11L, "task-1", " ", "message"));

        verify(appMapper, never()).claimGenerationState(
                any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void activeExecutionFenceEpochMustBeForwardedToOwnedWrites() {
        GenerationExecutionContextService executionContextService =
                mock(GenerationExecutionContextService.class);
        when(executionContextService.getExecutionFence("task-1")).thenReturn(Optional.of(
                new GenerationExecutionFence("task-1", "worker-a", 7L)));
        stateService = new GenerationAppStateService(
                appMapper, runtimeProperties, executionContextService, FIXED_CLOCK);
        when(appMapper.updateOwnedGenerationStage(
                eq(11L), eq("task-1"), eq(7L), eq("build"), eq("building"), any()))
                .thenReturn(1);

        stateService.updateOwnedGenerationStage(11L, "task-1", "build", "building");

        verify(appMapper).updateOwnedGenerationStage(
                eq(11L), eq("task-1"), eq(7L), eq("build"), eq("building"), any());
    }
}
