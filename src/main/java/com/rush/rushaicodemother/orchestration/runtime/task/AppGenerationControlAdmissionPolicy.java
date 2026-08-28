package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 在任何模型成本发生前执行应用暂停、急停、并发和预算门禁。 */
@Component
@Order(0)
public class AppGenerationControlAdmissionPolicy implements GenerationTaskAdmissionPolicy {

    @Override
    public void assertMayAdmit(GenerationTaskAdmissionContext context) {
        assertAllowed(context.snapshot(), context.quote().reservedCredit());
    }

    @Override
    public void assertMayPreflight(GenerationTaskPreflightAdmissionContext context) {
        assertAllowed(context.snapshot(), context.upperBoundQuote().reservedCredit());
    }

    private void assertAllowed(GenerationTaskAdmissionSnapshot snapshot, long requiredCredit) {
        if (snapshot.appEmergencyStopped()) {
            throw rejected("当前应用已紧急停止生成，请联系应用管理员确认现场状态");
        }
        if (snapshot.appGenerationPaused()) {
            throw rejected("当前应用已暂停新生成任务");
        }
        if (snapshot.appNonTerminalTasks() >= snapshot.appMaxConcurrentTasks()) {
            throw rejected("当前应用同时进行中的生成任务已达到上限（"
                    + snapshot.appMaxConcurrentTasks() + "）");
        }
        Long monthlyLimit = snapshot.appMonthlyCreditLimit();
        if (monthlyLimit == null) {
            return;
        }
        long remaining = Math.max(0L, monthlyLimit - snapshot.appMonthlyCreditUsage());
        if (requiredCredit > remaining) {
            throw rejected("当前应用本月生成预算不足，本任务需要预留 "
                    + requiredCredit + " 积分");
        }
    }

    private BusinessException rejected(String message) {
        return new BusinessException(ErrorCode.OPERATION_ERROR, message);
    }
}
