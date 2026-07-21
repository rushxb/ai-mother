package com.rush.rushaicodemother.model.enums;

/**
 * 用户积分流水业务类型。
 *
 * <p>枚举名称与数据库持久化值保持一致，避免调用方传入任意字符串破坏账务分类。</p>
 */
public enum UserCreditTransactionType {
    ACCOUNT_INITIALIZATION,
    ADMIN_ADJUST,
    GENERATION_CHARGE,
    GENERATION_RESERVATION,
    GENERATION_SETTLEMENT
}
