package com.rush.rushaicodemother.service.credit;

/** Immutable, idempotent reservation request bound to one generation task. */
public record GenerationCreditReservationCommand(
        String taskId,
        Long userId,
        long reservedCredit,
        String pricingReference
) {
}
