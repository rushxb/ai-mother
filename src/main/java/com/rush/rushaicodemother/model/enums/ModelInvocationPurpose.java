package com.rush.rushaicodemother.model.enums;

/** 物理模型调用的稳定业务目的；用于成本归因，禁止调用方写任意字符串。 */
public enum ModelInvocationPurpose {
    GENERATION,
    PROMPT_OPTIMIZATION,
    APP_NAME_ENRICHMENT,
    CONNECTION_TEST
}
