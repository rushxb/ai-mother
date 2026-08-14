package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationCreditMetricsCollectorTest {

    @Test
    void reservationSettlementDifferenceMustBeObservableWithoutBusinessIds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GenerationCreditMetricsCollector collector = new GenerationCreditMetricsCollector(registry);

        collector.recordReservationSettlement(5, 2);
        collector.recordReservationSettlement(2, 4);
        collector.recordReservationSettlement(3, 3);

        assertEquals(3, registry.find("generation_credit_reservation_settlement_difference_total")
                .tag("direction", "refund").counter().count(), 0.001);
        assertEquals(2, registry.find("generation_credit_reservation_settlement_difference_total")
                .tag("direction", "additional_charge").counter().count(), 0.001);
        assertEquals(1, registry.find("generation_credit_settlements_total")
                .tag("direction", "exact").counter().count(), 0.001);
    }
}
