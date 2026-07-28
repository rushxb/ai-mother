package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/** 以原子方式提交应用程序可见的元数据和持久的发布传奇。 */
@Service
public class GenerationWorkspacePublicationMetadataService
        implements GenerationWorkspacePublicationCommitter {

    private final GenerationAppStateService generationAppStateService;
    private final GenerationWorkspacePublicationJournalRepository journalRepository;
    private final Clock clock;

    @Autowired
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

    /**
 * 处理提交。
 *
 * @param pointer {@code pointer} 对应的调用参数
 */
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
