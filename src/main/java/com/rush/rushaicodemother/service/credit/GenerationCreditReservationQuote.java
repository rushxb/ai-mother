package com.rush.rushaicodemother.service.credit;

/** 可审计的执行前报价；最终结算仍然使用计量模型代币。 */
public record GenerationCreditReservationQuote(
        long estimatedTokens,
        long reservedCredit,
        String pricingReference
) {
}
