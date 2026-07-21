package com.rush.rushaicodemother.service.credit;

/** Auditable pre-execution quote; final settlement still uses measured model tokens. */
public record GenerationCreditReservationQuote(
        long estimatedTokens,
        long reservedCredit,
        String pricingReference
) {
}
