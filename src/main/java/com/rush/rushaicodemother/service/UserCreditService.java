package com.rush.rushaicodemother.service;

import com.rush.rushaicodemother.service.credit.AdminCreditAdjustmentCommand;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationCommand;

public interface UserCreditService {

    /** 校验指定用户存在且仍有可用积分。 */
    void ensureHasCredit(Long userId);

    /**
     * 校验用户可用积分不低于指定上限。
     *
     * <p>旧实现只能兼容单积分检查；更高额度必须显式实现，禁止静默降级。</p>
     */
    default void ensureHasCredit(Long userId, long requiredCredit) {
        if (requiredCredit <= 0) {
            throw new IllegalArgumentException("所需积分必须大于 0");
        }
        if (requiredCredit > 1) {
            throw new IllegalStateException("当前积分服务未实现额度上限检查");
        }
        ensureHasCredit(userId);
    }

    /**
     * 为新创建的用户初始化积分余额和对应流水。
     *
     * @param userId        新用户 ID
     * @param initialCredit 初始积分，必须大于 0
     * @param adminUserId   执行创建操作的管理员 ID
     */
    void initializeCredit(Long userId, Long initialCredit, Long adminUserId);

    /** 管理员幂等调整已有用户积分。 */
    long adjustCreditByAdmin(AdminCreditAdjustmentCommand command);

    /** 根据任务模型调用记录结算积分。 */
    void chargeGenerationTask(String taskId);

    /** 在任务进入 durable queue 前原子冻结预计积分。 */
    void reserveGenerationTask(GenerationCreditReservationCommand command);
}
