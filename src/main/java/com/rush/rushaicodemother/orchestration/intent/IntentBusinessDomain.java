package com.rush.rushaicodemother.orchestration.intent;

/**
 * 一次意图解析冻结的主业务领域。
 *
 * <p>这里只保存有限枚举，不携带原始 Prompt 或生成技术细节。API 契约如何把领域映射为
 * 实体、字段与端点，仍由契约模块负责，避免意图层反向依赖代码生成实现。</p>
 */
public enum IntentBusinessDomain {
    GENERAL,
    PRODUCT,
    ORDER,
    TASK
}
