package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.config.AiToolApprovalProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolApprovalExpirationCoordinatorTest {

    @Test
    void expirationMustDispatchEveryDurableWaitingContinuation() {
        ToolApprovalService approvalService = mock(ToolApprovalService.class);
        ToolApprovalRepository repository = mock(ToolApprovalRepository.class);
        GenerationToolContinuationScheduler scheduler = mock(GenerationToolContinuationScheduler.class);
        AiToolApprovalProperties properties = new AiToolApprovalProperties();
        properties.setExpirationBatchSize(25);
        ToolApprovalRecord expired = mock(ToolApprovalRecord.class);
        when(repository.findWaitingContinuations(25)).thenReturn(List.of(expired));
        ToolApprovalExpirationCoordinator coordinator = new ToolApprovalExpirationCoordinator(
                approvalService, repository, scheduler, properties);

        coordinator.expireAndResume();

        verify(approvalService).expireApprovals();
        verify(repository).findWaitingContinuations(25);
        verify(scheduler).schedule(expired);
    }
}
