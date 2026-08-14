package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 生成积分预留与实结差异的低基数遥测。 */
@Component
@RequiredArgsConstructor
public class GenerationCreditMetricsCollector {

    private final MeterRegistry meterRegistry;

    public void recordReservationSettlement(long reservedCredit, long actualCredit) {
        long difference = actualCredit - reservedCredit;
        String direction = difference == 0 ? "exact"
                : difference > 0 ? "additional_charge" : "refund";
        Counter.builder("generation_credit_settlements_total")
                .description("Generation credit reservation settlement outcomes")
                .tag("direction", direction)
                .register(meterRegistry)
                .increment();
        if (difference != 0) {
            Counter.builder("generation_credit_reservation_settlement_difference_total")
                    .description("Absolute generation credit reservation and settlement difference")
                    .tag("direction", direction)
                    .register(meterRegistry)
                    .increment(safeAbsolute(difference));
        }
    }

    /** 记录 Provider 成本中最终由用户承担与产品减免的 token 数。 */
    public void recordProviderCostSettlement(long providerObservedTokens,
                                             long chargeableTokens,
                                             long waivedTokens) {
        if (providerObservedTokens < 0 || chargeableTokens < 0 || waivedTokens < 0
                || Math.addExact(chargeableTokens, waivedTokens) != providerObservedTokens) {
            throw new IllegalArgumentException("Provider 成本结算指标不守恒");
        }
        recordProviderTokenDisposition("billed", chargeableTokens);
        recordProviderTokenDisposition("waived", waivedTokens);
    }

    private void recordProviderTokenDisposition(String disposition, long tokenCount) {
        if (tokenCount == 0) {
            return;
        }
        Counter.builder("generation_provider_cost_tokens_total")
                .description("Observed provider cost tokens by user billing disposition")
                .tag("disposition", disposition)
                .register(meterRegistry)
                .increment(tokenCount);
    }

    private long safeAbsolute(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }
}
