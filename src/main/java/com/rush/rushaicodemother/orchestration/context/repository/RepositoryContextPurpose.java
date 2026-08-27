package com.rush.rushaicodemother.orchestration.context.repository;

/** 项目上下文的消费场景，以及该场景允许占用的默认模型 Token 预算。 */
public enum RepositoryContextPurpose {

    READ_ONLY(15_360),
    LIGHT_EDIT(15_360),
    AGENT_EDIT(15_360),
    HEAVY(2_500);

    private final int defaultTokenBudget;

    RepositoryContextPurpose(int defaultTokenBudget) {
        this.defaultTokenBudget = defaultTokenBudget;
    }

    public int defaultTokenBudget() {
        return defaultTokenBudget;
    }
}
