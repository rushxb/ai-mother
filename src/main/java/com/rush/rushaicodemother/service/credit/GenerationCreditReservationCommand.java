package com.rush.rushaicodemother.service.credit;

/** 绑定到一个生成任务的不可变的、幂等的预留请求。 */
public record GenerationCreditReservationCommand(
        String taskId,
        Long userId,
        Long tenantId,
        Long appId,
        long reservedCredit,
        String pricingReference
) {

    /** 兼容迁移期尚未显式传入应用身份的调用方。 */
    public GenerationCreditReservationCommand(String taskId,
                                               Long userId,
                                               Long tenantId,
                                               long reservedCredit,
                                               String pricingReference) {
        this(taskId, userId, tenantId, null, reservedCredit, pricingReference);
    }

    /** 兼容尚未携带租户身份的旧调用方。 */
    public GenerationCreditReservationCommand(String taskId,
                                              Long userId,
                                              long reservedCredit,
                                              String pricingReference) {
        this(taskId, userId, null, null, reservedCredit, pricingReference);
    }
}
