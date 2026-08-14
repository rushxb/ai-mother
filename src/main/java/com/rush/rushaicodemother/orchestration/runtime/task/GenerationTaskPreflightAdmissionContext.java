package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationQuote;

import java.util.Objects;

/** 模型澄清前使用的只读用户、租户容量与成本判断事实。 */
public record GenerationTaskPreflightAdmissionContext(
        Long tenantId,
        Long userId,
        CodeGenTypeEnum targetType,
        IntentProfile intentProfile,
        GenerationTaskAdmissionSnapshot snapshot,
        GenerationCreditReservationQuote upperBoundQuote
) {

    public GenerationTaskPreflightAdmissionContext {
        if (tenantId == null || tenantId <= 0 || userId == null || userId <= 0) {
            throw new IllegalArgumentException("preflight 准入身份必须为正数");
        }
        Objects.requireNonNull(targetType, "preflight 目标类型不能为空");
        Objects.requireNonNull(intentProfile, "preflight 意图画像不能为空");
        Objects.requireNonNull(snapshot, "preflight 准入快照不能为空");
        Objects.requireNonNull(upperBoundQuote, "preflight 成本上限不能为空");
    }
}
