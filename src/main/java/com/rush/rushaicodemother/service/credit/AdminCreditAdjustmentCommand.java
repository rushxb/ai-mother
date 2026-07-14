package com.rush.rushaicodemother.service.credit;

/** 管理员积分调整命令，requestId 用于保证客户端重试幂等。 */
public record AdminCreditAdjustmentCommand(
        String requestId,
        Long userId,
        Long changeAmount,
        String remark,
        Long adminUserId
) {
}
