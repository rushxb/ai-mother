package com.rush.rushaicodemother.orchestration.runtime.task;

/** 事务准入结果用于防止幂等重放后的重复调度。 */
public record GenerationTaskAdmissionResult(
        GenerationTaskSubmissionReceipt submission,
        Disposition disposition
) {

    /** 创建生成任务准入结果实例并完成必要的依赖和初始状态设置。 */
    public GenerationTaskAdmissionResult {
        if (submission == null || disposition == null) {
            throw new IllegalArgumentException("生成任务准入结果不完整");
        }
    }

    public static GenerationTaskAdmissionResult created(GenerationTaskSubmissionReceipt submission) {
        return new GenerationTaskAdmissionResult(submission, Disposition.CREATED);
    }

    public static GenerationTaskAdmissionResult reused(GenerationTaskSubmissionReceipt submission) {
        return new GenerationTaskAdmissionResult(submission, Disposition.REUSED);
    }

    public boolean created() {
        return disposition == Disposition.CREATED;
    }

    public String taskId() {
        return submission.taskId();
    }

    public String route() {
        return submission.route();
    }

    public enum Disposition {
        CREATED,
        REUSED
    }
}
