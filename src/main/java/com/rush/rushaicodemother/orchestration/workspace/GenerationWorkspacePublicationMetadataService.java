package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/** Atomically commits application-visible metadata and the durable publication saga. */
@Service
public class GenerationWorkspacePublicationMetadataService
        implements GenerationWorkspacePublicationCommitter {

    private final GenerationAppStateService generationAppStateService;
    private final GenerationWorkspacePublicationJournalRepository journalRepository;
    private final Clock clock;

    public GenerationWorkspacePublicationMetadataService(
            GenerationAppStateService generationAppStateService,
            GenerationWorkspacePublicationJournalRepository journalRepository) {
        this(generationAppStateService, journalRepository, Clock.systemUTC());
    }

    GenerationWorkspacePublicationMetadataService(
            GenerationAppStateService generationAppStateService,
            GenerationWorkspacePublicationJournalRepository journalRepository,
            Clock clock) {
        this.generationAppStateService = Objects.requireNonNull(
                generationAppStateService, "generationAppStateService");
        this.journalRepository = Objects.requireNonNull(journalRepository, "journalRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public void commit(GenerationWorkspacePublicationPointer pointer) {
        Objects.requireNonNull(pointer, "pointer");
        generationAppStateService.updateOwnedCodeGenType(
                pointer.appId(),
                pointer.taskId(),
                pointer.executionEpoch(),
                pointer.codeGenType()
        );
        journalRepository.markCommitted(pointer, clock.instant());
    }
}
