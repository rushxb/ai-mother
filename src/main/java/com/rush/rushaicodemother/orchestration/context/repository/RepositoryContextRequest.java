package com.rush.rushaicodemother.orchestration.context.repository;

/** 请求项目上下文进入模型边界时必须冻结的用途、查询和出站预算。 */
public record RepositoryContextRequest(
        RepositoryContextPurpose purpose,
        String query,
        int tokenBudget,
        boolean outboundAllowed
) {

    private static final int MIN_TOKEN_BUDGET = 256;

    public RepositoryContextRequest {
        if (purpose == null) {
            throw new IllegalArgumentException("项目上下文用途不能为空");
        }
        query = query == null ? "" : query.trim();
        if (tokenBudget < MIN_TOKEN_BUDGET) {
            throw new IllegalArgumentException("项目上下文 Token 预算不能小于 " + MIN_TOKEN_BUDGET);
        }
    }

    public static RepositoryContextRequest forPurpose(RepositoryContextPurpose purpose, String query) {
        if (purpose == null) {
            throw new IllegalArgumentException("项目上下文用途不能为空");
        }
        return new RepositoryContextRequest(purpose, query, purpose.defaultTokenBudget(), true);
    }
}
