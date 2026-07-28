package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionReceipt;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import reactor.core.publisher.Flux;

import java.util.Objects;

/**
 * 生成任务执行结果。
 */
public record GenerationTaskResult(
        GenerationTaskSubmissionReceipt submission,
        GenerationWorkspace workspace,
        Flux<GenerationStreamEvent> contentFlux,
        boolean created
) {

    /** 创建任务结果并确保提交回执与事件流始终可用。 */
    public GenerationTaskResult {
        Objects.requireNonNull(submission, "生成任务提交回执不能为空");
        Objects.requireNonNull(contentFlux, "生成任务事件流不能为空");
    }

    public GenerationTaskResult(GenerationTaskSubmissionReceipt submission,
                                GenerationWorkspace workspace,
                                Flux<GenerationStreamEvent> contentFlux) {
        this(submission, workspace, contentFlux, true);
    }

    public String taskId() {
        return submission.taskId();
    }

    public String route() {
        return submission.route();
    }
}
