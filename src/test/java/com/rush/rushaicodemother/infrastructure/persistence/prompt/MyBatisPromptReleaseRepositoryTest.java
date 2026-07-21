package com.rush.rushaicodemother.infrastructure.persistence.prompt;

import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseAction;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseConflictException;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseMutation;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseState;
import com.rush.rushaicodemother.mapper.AiPromptReleaseMapper;
import com.rush.rushaicodemother.model.entity.AiPromptReleaseEntity;
import com.rush.rushaicodemother.model.entity.AiPromptReleaseHistoryEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisPromptReleaseRepositoryTest {

    private static final String EVIDENCE_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void publishMustLockBundleCheckExpectedRevisionAndAppendAuditHistory() {
        AiPromptReleaseMapper mapper = mock(AiPromptReleaseMapper.class);
        MyBatisPromptReleaseRepository repository = new MyBatisPromptReleaseRepository(mapper);
        when(mapper.lockBundleRevision()).thenReturn(7L);
        when(mapper.selectCurrentForUpdate("test-prompt")).thenReturn(current(5L));
        when(mapper.advanceBundle(anyLong(), anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.upsertCurrent(any())).thenReturn(2);
        when(mapper.insertHistory(any())).thenReturn(1);

        var result = repository.publish(new PromptReleaseMutation(
                "test-prompt",
                new PromptReleaseSpec("v2", "v3", 10),
                5L,
                19L,
                "canary v3",
                PromptReleaseAction.PUBLISH,
                null,
                EVIDENCE_ID
        ));

        assertEquals(8L, result.revision());
        assertEquals("v2", result.release().stableVersion());
        assertEquals("v3", result.release().canaryVersion());
        verify(mapper).advanceBundle(anyLong(), anyLong(), anyLong(), any());

        ArgumentCaptor<AiPromptReleaseEntity> currentCaptor =
                ArgumentCaptor.forClass(AiPromptReleaseEntity.class);
        verify(mapper).upsertCurrent(currentCaptor.capture());
        assertEquals(8L, currentCaptor.getValue().getRevision());
        assertEquals(10, currentCaptor.getValue().getCanaryPercentage());

        ArgumentCaptor<AiPromptReleaseHistoryEntity> historyCaptor =
                ArgumentCaptor.forClass(AiPromptReleaseHistoryEntity.class);
        verify(mapper).insertHistory(historyCaptor.capture());
        assertEquals("PUBLISH", historyCaptor.getValue().getAction());
        assertEquals(8L, historyCaptor.getValue().getRevision());
        assertEquals(EVIDENCE_ID, historyCaptor.getValue().getEvidenceId());
    }

    @Test
    void staleExpectedRevisionMustFailBeforeAnyWrite() {
        AiPromptReleaseMapper mapper = mock(AiPromptReleaseMapper.class);
        MyBatisPromptReleaseRepository repository = new MyBatisPromptReleaseRepository(mapper);
        when(mapper.lockBundleRevision()).thenReturn(7L);
        when(mapper.selectCurrentForUpdate("test-prompt")).thenReturn(current(6L));

        PromptReleaseConflictException exception = assertThrows(
                PromptReleaseConflictException.class,
                () -> repository.publish(new PromptReleaseMutation(
                        "test-prompt",
                        new PromptReleaseSpec("v2", "", 0),
                        5L,
                        19L,
                        "rollback stable",
                        PromptReleaseAction.PUBLISH,
                        null,
                        EVIDENCE_ID
                ))
        );

        assertEquals(5L, exception.expectedRevision());
        assertEquals(6L, exception.actualRevision());
        verify(mapper, never()).advanceBundle(anyLong(), anyLong(), anyLong(), any());
        verify(mapper, never()).upsertCurrent(any());
    }

    @Test
    void loadCurrentAndHistoryMustMapDurableRowsWithoutPromptContent() {
        AiPromptReleaseMapper mapper = mock(AiPromptReleaseMapper.class);
        MyBatisPromptReleaseRepository repository = new MyBatisPromptReleaseRepository(mapper);
        when(mapper.selectBundleRevision()).thenReturn(8L);
        when(mapper.selectAllCurrent()).thenReturn(List.of(current(8L)));
        when(mapper.selectHistoryPage("test-prompt", 20)).thenReturn(List.of(history(8L)));

        PromptReleaseState state = repository.loadCurrent();

        assertEquals(8L, state.revision());
        assertEquals("v1", state.releases().get("test-prompt").release().stableVersion());
        var history = repository.listHistory("test-prompt", 20);
        assertEquals(1, history.size());
        assertEquals(PromptReleaseAction.ROLLBACK, history.getFirst().action());
        assertEquals(3L, history.getFirst().sourceRevision());
        assertTrue(history.getFirst().changeNote().contains("known-good"));
    }

    @Test
    void repositoryBoundaryMustRejectMalformedReleasePointersBeforeLocking() {
        AiPromptReleaseMapper mapper = mock(AiPromptReleaseMapper.class);
        MyBatisPromptReleaseRepository repository = new MyBatisPromptReleaseRepository(mapper);

        assertThrows(IllegalArgumentException.class, () -> repository.publish(
                new PromptReleaseMutation(
                        "test-prompt",
                        new PromptReleaseSpec("v1", "v2", 0),
                        0L,
                        19L,
                        "invalid canary state",
                        PromptReleaseAction.PUBLISH,
                        null,
                        EVIDENCE_ID
                )
        ));

        verify(mapper, never()).lockBundleRevision();
    }

    private AiPromptReleaseEntity current(long revision) {
        return AiPromptReleaseEntity.builder()
                .promptKey("test-prompt")
                .stableVersion("v1")
                .canaryPercentage(0)
                .revision(revision)
                .updatedBy(19L)
                .changeNote("known-good stable")
                .createTime(LocalDateTime.of(2026, 7, 17, 1, 0))
                .updateTime(LocalDateTime.of(2026, 7, 17, 2, 0))
                .build();
    }

    private AiPromptReleaseHistoryEntity history(long revision) {
        return AiPromptReleaseHistoryEntity.builder()
                .revision(revision)
                .promptKey("test-prompt")
                .stableVersion("v1")
                .canaryPercentage(0)
                .action("ROLLBACK")
                .sourceRevision(3L)
                .updatedBy(19L)
                .changeNote("restore known-good stable")
                .createTime(LocalDateTime.of(2026, 7, 17, 2, 0))
                .build();
    }
}
