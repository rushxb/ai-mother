package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * 管理暂定预览 Dev Server 会话在生成任务内的收口。
 *
 * <p>暂定预览的会话由 {@code DevServerValidationService} 以
 * {@code DevServerSessionOwnership#TASK_SCOPED} 启动，验证返回后刻意不停 —— 用户要能真的点开它。
 * 停止责任因此落在生成任务侧，而本类是这份责任的唯一承载者：把它独立成类而不是塞进
 * {@code GenerationPreviewMilestoneService}，是因为后者只负责事件与遥测，
 * 让它再管进程生命周期会把两种失败模式绑在一起（遥测失败不该影响进程清理，反之亦然）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GenerationProvisionalPreviewLifecycle {

    private final DevServerManager devServerManager;

    /**
     * 在发布移走执行工作区之前停止以该工作区为 root 的暂定预览。
     *
     * <p>停止条件收紧到「root 恰好是本次要发布的执行工作区」：用户手动打开的、以正式工作区为
     * root 的预览与本次发布无关，不该被连带停掉。因此这里按目录匹配停，而不是按 {@code appId} 无条件停。</p>
     *
     * @param session 生成会话；缺少执行工作区时视为没有暂定预览，直接返回
     * @return 确实停止了暂定预览时返回 {@code true}
     */
    public boolean stopBeforePublication(GenerationSession session) {
        if (session == null || session.executionWorkspace() == null) {
            return false;
        }
        GenerationExecutionWorkspace executionWorkspace = session.executionWorkspace();
        Path previewRoot = previewRootOf(executionWorkspace);
        boolean stopped = devServerManager.stopDevServerIfRootedAt(
                executionWorkspace.appId(), previewRoot);
        if (stopped) {
            log.info("发布前已停止暂定预览 Dev Server，taskId: {}, appId: {}, epoch: {}",
                    executionWorkspace.taskId(), executionWorkspace.appId(),
                    executionWorkspace.executionEpoch());
        }
        return stopped;
    }

    /** 终态时停止指定执行纪元持有的暂定预览。 */
    public boolean stopForTerminal(Long appId, GenerationExecutionFence fence) {
        if (appId == null || fence == null) {
            return false;
        }
        boolean stopped = devServerManager.stopDevServerIfOwnedBy(appId, fence);
        if (stopped) {
            log.info("终态已停止任务级暂定预览 Dev Server，taskId: {}, appId: {}, epoch: {}",
                    fence.taskId(), appId, fence.executionEpoch());
        }
        return stopped;
    }

    /**
     * 推导暂定预览会话的 root 目录。
     *
     * <p>口径必须与 {@code DevServerProjectLocator#locate} 完全一致：全栈项目的 Dev Server
     * 跑在前端子目录而非工作区根目录，若这里只取根目录，全栈任务的目录比对会永远不匹配，
     * 停止变成静默的空操作 —— 那正是本轮要修的「无声失效」问题的另一种形态。</p>
     */
    private Path previewRootOf(GenerationExecutionWorkspace executionWorkspace) {
        GenerationWorkspace workspace = executionWorkspace.workspace();
        return executionWorkspace.codeGenType() == CodeGenTypeEnum.FULL_STACK_PROJECT
                ? workspace.frontendRootPath()
                : workspace.canonicalRootPath();
    }
}
