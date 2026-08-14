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

    private long safeAbsolute(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }
}
