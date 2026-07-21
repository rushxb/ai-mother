package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class GenerationWorkspacePublicationMetadataServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T07:00:00Z");

    @Test
    void metadataAndJournalMustCommitThroughTheExactExecutionEpoch() {
        GenerationAppStateService appStateService = mock(GenerationAppStateService.class);
        GenerationWorkspacePublicationJournalRepository journal =
                mock(GenerationWorkspacePublicationJournalRepository.class);
        GenerationWorkspacePublicationMetadataService service =
                new GenerationWorkspacePublicationMetadataService(
                        appStateService,
                        journal,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        GenerationWorkspacePublicationPointer pointer = pointer();

        service.commit(pointer);

        var ordered = inOrder(appStateService, journal);
        ordered.verify(appStateService).updateOwnedCodeGenType(
                11L, "task-1", 5L, CodeGenTypeEnum.VUE_PROJECT);
        ordered.verify(journal).markCommitted(pointer, NOW);
    }

    private GenerationWorkspacePublicationPointer pointer() {
        return new GenerationWorkspacePublicationPointer(
                GenerationWorkspacePublicationPointer.CURRENT_SCHEMA_VERSION,
                11L,
                CodeGenTypeEnum.VUE_PROJECT,
                "task-1",
                5L,
                NOW
        );
    }
}
