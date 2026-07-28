package com.rush.rushaicodemother.service.prompt;

import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseAction;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseMutation;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRecord;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRepository;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationReleaseEvidenceVerifier;
import com.rush.rushaicodemother.service.release.AiReleaseCoordinationLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在同一事务中完成发布锁定、证据重放和 Prompt 指针持久化。 */
@Service
@RequiredArgsConstructor
public class PromptReleaseTransactionCoordinator {

    private final AiReleaseCoordinationLock coordinationLock;
    private final PromptReleaseRepository repository;
    private final GenerationReleaseEvidenceVerifier evidenceVerifier;

    /**
 * 在事务边界内变更提示词发布事务协调器。
 *
 * @param mutation 变更
 * @return 提示词发布事务协调器
 */
    @Transactional(rollbackFor = Exception.class)
    public PromptReleaseRecord mutate(PromptReleaseMutation mutation) {
        coordinationLock.acquire();
        if (mutation.action() == PromptReleaseAction.PUBLISH) {
            evidenceVerifier.requirePassed(
                    mutation.evidenceId(),
                    new GenerationBenchmarkEvidenceCandidate.PromptRelease(
                            mutation.promptKey(), mutation.release())
            );
        }
        return repository.publish(mutation);
    }
}
