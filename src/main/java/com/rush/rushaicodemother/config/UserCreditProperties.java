package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.Data;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;

/** 用户积分计费规则的固定配置。 */
@Data
@Component
@Validated
public class UserCreditProperties {

    public static final long TOKENS_PER_CREDIT = 100_000L;
    public static final int SETTLEMENT_BATCH_SIZE = 100;
    public static final Duration PREFLIGHT_RESERVATION_RECOVERY_DELAY = Duration.ofMinutes(5);

    /** 后台结算扫描间隔，供 {@code @Scheduled} 注解引用的固定字面量。 */
    public static final String SETTLEMENT_SCAN_INTERVAL = "30s";

    /** 每个积分可覆盖的 token 数。 */
    @Min(1)
    private long tokensPerCredit = TOKENS_PER_CREDIT;

    /** 一张后台结算通证可核对最大终端任务数。 */
    @Min(1)
    @Max(500)
    private int settlementBatchSize = SETTLEMENT_BATCH_SIZE;

    /** 预检预授权超过该时间仍无正式任务时，后台按 Provider 账本回收。 */
    @NotNull
    @DurationMin(seconds = 1)
    private Duration preflightReservationRecoveryDelay = PREFLIGHT_RESERVATION_RECOVERY_DELAY;
}
