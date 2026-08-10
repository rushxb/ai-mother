package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.preview.GenerationPreviewMilestoneService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 在原子工作区发布成功后，统一提交用户可见版本及首预览里程碑。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GenerationWorkspaceReleaseService {

    private final GenerationWorkspacePublicationService publicationService;
    private final GenerationWorkspacePublicationMetadataService publicationMetadataService;
    private final GenerationPreviewMilestoneService previewMilestoneService;

    /**
 * 发布已经通过验证的生成工作区。
 *
 * @param session 会话
 * @param targetType 目标类型
 * @return 生成工作区发布
 */
    public GenerationWorkspacePublicationResult releaseVerified(
            GenerationSession session,
            CodeGenTypeEnum targetType
    ) {
        if (session == null || session.executionWorkspace() == null
                || session.executionContext() == null
                || session.executionContext().executionFence() == null
                || targetType == null) {
            throw new GenerationExecutionPolicyException(
                    "托管生成发布上下文不完整");
        }
        GenerationExecutionWorkspace workspace = session.executionWorkspace();
        if (workspace.codeGenType() != targetType) {
            throw new GenerationExecutionPolicyException(
                    "生成发布类型与执行工作区不一致");
        }
        session.throwIfCancelled();
        GenerationWorkspacePublicationResult result =
                publicationService.publishWithMetadata(session, publicationMetadataService);
        publishFirstPreviewSafely(session, targetType);
        return result;
    }

    /** 发布{@code First}预览安全处理。 */
    private void publishFirstPreviewSafely(GenerationSession session, CodeGenTypeEnum targetType) {
        try {
            if (targetType == CodeGenTypeEnum.BACKEND_PROJECT) {
                previewMilestoneService.publishBuildReady(session, targetType);
                return;
            }
            previewMilestoneService.publishRuntimeReady(session, targetType);
        } catch (RuntimeException exception) {
            log.warn("工作区已经发布，但首预览里程碑通知失败，taskId: {}, targetType: {}",
                    session.taskId(), targetType.getValue(), LogExceptionSanitizer.sanitize(exception));
        }
    }
}
