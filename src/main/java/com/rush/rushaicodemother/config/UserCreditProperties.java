package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 用户积分计费规则的固定配置。 */
@Data
@Component
@Validated
public class UserCreditProperties {

    public static final long TOKENS_PER_CREDIT = 100_000L;
    public static final int SETTLEMENT_BATCH_SIZE = 100;

    /** 后台结算扫描间隔，供 {@code @Scheduled} 注解引用的固定字面量。 */
    public static final String SETTLEMENT_SCAN_INTERVAL = "30s";

    /** 每个积分可覆盖的 token 数。 */
    @Min(1)
    private long tokensPerCredit = TOKENS_PER_CREDIT;

    /** 一张后台结算通证可核对最大终端任务数。 */
    @Min(1)
    @Max(500)
    private int settlementBatchSize = SETTLEMENT_BATCH_SIZE;
}
