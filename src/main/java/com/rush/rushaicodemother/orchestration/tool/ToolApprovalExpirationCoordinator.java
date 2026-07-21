package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.config.AiToolApprovalProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expires stale approvals and resumes their waiting conversations with an explicit timeout result. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolApprovalExpirationCoordinator {

    private final ToolApprovalService toolApprovalService;
    private final ToolApprovalRepository approvalRepository;
    private final GenerationToolContinuationScheduler continuationScheduler;
    private final AiToolApprovalProperties properties;

    @Scheduled(fixedDelayString = "${app.ai-tool-approval.expiration-scan-interval:1m}")
    public void expireAndResume() {
        toolApprovalService.expireApprovals();
        for (ToolApprovalRecord expired : approvalRepository.findWaitingContinuations(
                properties.getExpirationBatchSize())) {
            try {
                continuationScheduler.schedule(expired);
            } catch (RuntimeException continuationFailure) {
                log.warn("Expired tool approval continuation will be retried, taskId: {}, approvalId: {}, error: {}",
                        expired.taskId(), expired.approvalId(),
                        LogExceptionSanitizer.sanitizeMessage(continuationFailure));
            }
        }
    }
}
