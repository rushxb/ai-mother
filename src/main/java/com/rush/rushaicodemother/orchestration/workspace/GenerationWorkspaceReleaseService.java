package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Coordinates filesystem publication with the application-visible code-generation type. */
@Service
@RequiredArgsConstructor
public class GenerationWorkspaceReleaseService {

    private final GenerationWorkspacePublicationService publicationService;
    private final GenerationWorkspacePublicationMetadataService publicationMetadataService;

    public GenerationWorkspacePublicationResult release(
            GenerationSession session,
            CodeGenTypeEnum targetType
    ) {
        if (session == null || session.executionWorkspace() == null
                || session.executionContext() == null
                || session.executionContext().executionFence() == null
                || targetType == null) {
            throw new GenerationExecutionPolicyException(
                    "managed generation release context is incomplete");
        }
        GenerationExecutionWorkspace workspace = session.executionWorkspace();
        if (workspace.codeGenType() != targetType) {
            throw new GenerationExecutionPolicyException(
                    "generation release type does not match the execution workspace");
        }
        session.throwIfCancelled();
        return publicationService.publishWithMetadata(session, publicationMetadataService);
    }
}
