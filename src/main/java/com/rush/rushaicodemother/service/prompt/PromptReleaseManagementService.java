package com.rush.rushaicodemother.service.prompt;

import com.rush.rushaicodemother.model.vo.PromptCatalogAdminVO;
import com.rush.rushaicodemother.model.vo.PromptReleaseHistoryVO;
import com.rush.rushaicodemother.model.vo.PromptReleaseMutationVO;

import java.util.List;

/** Administrator use cases for audited stable/canary prompt releases. */
public interface PromptReleaseManagementService {

    PromptCatalogAdminVO getOverview();

    PromptReleaseMutationVO publish(PublishCommand command, long operatorUserId);

    PromptReleaseMutationVO rollback(RollbackCommand command, long operatorUserId);

    List<PromptReleaseHistoryVO> listHistory(String promptKey, int limit);

    record PublishCommand(
            String promptKey,
            String stableVersion,
            String canaryVersion,
            int canaryPercentage,
            long expectedRevision,
            String changeNote,
            String evidenceId
    ) {
    }

    record RollbackCommand(
            String promptKey,
            long targetRevision,
            long expectedRevision,
            String changeNote
    ) {
    }
}
