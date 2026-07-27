package com.rush.rushaicodemother.service.credit;

/** 绑定到一个生成任务的不可变的、幂等的预留请求。 */
public record GenerationCreditReservationCommand(
        String taskId,
        Long userId,
        long reservedCredit,
        String pricingReference
) {
}
