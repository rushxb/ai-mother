package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.model.enums.ModelInvocationBillingMode;
import com.rush.rushaicodemother.model.enums.ModelInvocationPurpose;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 监控上下文（需要传递的数据）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitorContext implements Serializable {

    private String userId;

    private String appId;

    private String taskId;

    private ModelInvocationPurpose invocationPurpose;

    private ModelInvocationBillingMode billingMode;

    private String billingExemptionReason;

    /** 兼容生成链路的既有上下文构造；缺省语义由 listener 归一化为 GENERATION/BILLABLE。 */
    public MonitorContext(String userId, String appId, String taskId) {
        this.userId = userId;
        this.appId = appId;
        this.taskId = taskId;
    }

    @Serial
    private static final long serialVersionUID = 1L;
}
