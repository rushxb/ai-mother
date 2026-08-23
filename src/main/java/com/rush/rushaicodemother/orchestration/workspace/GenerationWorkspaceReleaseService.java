package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommand;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationDeferredException;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalIntentService;
import com.rush.rushaicodemother.orchestration.preview.GenerationPreviewMilestoneService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 在原子工作区发布成功后，统一提交用户可见版本及首预览里程碑。 */
@Service
@Slf4j
public class GenerationWorkspaceReleaseService {

    private final GenerationWorkspacePublicationService publicationService;
    private final GenerationWorkspacePublicationMetadataService publicationMetadataService;
    private final GenerationPreviewMilestoneService previewMilestoneService;
    private final GenerationProvisionalPreviewLifecycle provisionalPreviewLifecycle;
    private final GenerationTerminalIntentService terminalIntentService;

    @Autowired
    public GenerationWorkspaceReleaseService(
            GenerationWorkspacePublicationService publicationService,
            GenerationWorkspacePublicationMetadataService publicationMetadataService,
            GenerationPreviewMilestoneService previewMilestoneService,
            GenerationProvisionalPreviewLifecycle provisionalPreviewLifecycle,
            GenerationTerminalIntentService terminalIntentService) {
        this.publicationService = publicationService;
        this.publicationMetadataService = publicationMetadataService;
        this.previewMilestoneService = previewMilestoneService;
        this.provisionalPreviewLifecycle = provisionalPreviewLifecycle;
        this.terminalIntentService = terminalIntentService;
    }

    /** 遗留单测构造入口；生产发布必须注入终态意图服务。 */
    GenerationWorkspaceReleaseService(
            GenerationWorkspacePublicationService publicationService,
            GenerationWorkspacePublicationMetadataService publicationMetadataService,
            GenerationPreviewMilestoneService previewMilestoneService,
            GenerationProvisionalPreviewLifecycle provisionalPreviewLifecycle) {
        this(publicationService, publicationMetadataService, previewMilestoneService,
                provisionalPreviewLifecycle, null);
    }

    /** 遗留测试与非托管发布兼容入口。 */
    @Deprecated
    public GenerationWorkspacePublicationResult releaseVerified(
            GenerationSession session,
            CodeGenTypeEnum targetType) {
        if (terminalIntentService != null) {
            throw new GenerationExecutionPolicyException("托管生成发布必须提供终态意图");
        }
        return releaseVerifiedInternal(session, targetType, null);
    }

    /**
 * 发布已经通过验证的生成工作区。
 *
 * @param session 会话
 * @param targetType 目标类型
 * @return 生成工作区发布
 */
    public GenerationWorkspacePublicationResult releaseVerified(
            GenerationSession session,
            CodeGenTypeEnum targetType,
            GenerationFinalizationCommand terminalIntent
    ) {
        return releaseVerifiedInternal(session, targetType, terminalIntent);
    }

    private GenerationWorkspacePublicationResult releaseVerifiedInternal(
            GenerationSession session,
            CodeGenTypeEnum targetType,
            GenerationFinalizationCommand terminalIntent) {
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
        if (terminalIntentService != null) {
            if (terminalIntent == null
                    || terminalIntent.status() != com.rush.rushaicodemother.model.enums.GenerationTaskStatus.SUCCESS
                    || !session.taskId().equals(terminalIntent.taskId())) {
                throw new GenerationExecutionPolicyException("生成发布终态意图不完整");
            }
            terminalIntentService.prepare(terminalIntent);
        }
        stopProvisionalPreviewSafely(session);
        GenerationWorkspacePublicationResult result;
        try {
            result = publicationService.publishWithMetadata(session, publicationMetadataService);
        } catch (RuntimeException publicationFailure) {
            handlePublicationFailure(terminalIntent, publicationFailure);
            throw publicationFailure;
        }
        publishFirstPreviewSafely(session, targetType);
        return result;
    }

    /**
     * 发布完整回滚后才能撤销 SUCCESS 意图；否则保留现场等待 journal 对账。
     */
    private void handlePublicationFailure(GenerationFinalizationCommand terminalIntent,
                                          RuntimeException publicationFailure) {
        if (terminalIntentService == null || terminalIntent == null) {
            return;
        }
        if (publicationFailure instanceof GenerationWorkspacePublicationException classified
                && !classified.safelyRolledBack()) {
            throw deferredPublication(classified);
        }
        try {
            if (!terminalIntentService.abortPrepared(terminalIntent)) {
                throw deferredPublication(publicationFailure);
            }
        } catch (GenerationFinalizationDeferredException deferred) {
            throw deferred;
        } catch (RuntimeException abortFailure) {
            publicationFailure.addSuppressed(abortFailure);
            throw deferredPublication(publicationFailure);
        }
    }

    private GenerationFinalizationDeferredException deferredPublication(Throwable cause) {
        return new GenerationFinalizationDeferredException(
                "发布结果待对账，保留已冻结终态意图", cause);
    }

    /**
     * 发布前停止以执行工作区为 root 的暂定预览 Dev Server。
     *
     * <p>必须停，且必须在发布之前停：发布会把执行工作区整体 move 到版本目录，
     * 而 Linux 上 Vite 进程会继续持有被移走的旧 inode —— 预览既不报错也不更新，
     * 用户看到一份无声的过期内容，这比预览直接失效更难排查。</p>
     *
     * <p>停止失败只记日志不打断发布：发布是用户可见成果的唯一提交点，
     * 不能被一个体验增强进程的清理失败拖垮。残留进程由 Dev Server 心跳回收兜底 ——
     * 目录发布后即消失，回收判据 {@code workspace_directory_missing} 必然命中。</p>
     */
    private void stopProvisionalPreviewSafely(GenerationSession session) {
        try {
            provisionalPreviewLifecycle.stopBeforePublication(session);
        } catch (RuntimeException exception) {
            log.warn("发布前停止暂定预览失败，继续发布并交由心跳回收兜底，taskId: {}",
                    session.taskId(), LogExceptionSanitizer.sanitize(exception));
        }
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
