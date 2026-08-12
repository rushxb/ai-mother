package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationQuote;

import java.util.Objects;

/** 所有生成准入策略共享的不可变判断上下文。 */
public record GenerationTaskAdmissionContext(
        GenerationTaskCommand command,
        GenerationTaskAdmissionSnapshot snapshot,
        GenerationCreditReservationQuote quote
) {
    public GenerationTaskAdmissionContext {
        Objects.requireNonNull(command, "生成任务命令不能为空");
        Objects.requireNonNull(snapshot, "生成任务准入快照不能为空");
        Objects.requireNonNull(quote, "生成任务积分报价不能为空");
    }
}
