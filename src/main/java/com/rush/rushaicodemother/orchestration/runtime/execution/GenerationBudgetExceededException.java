package com.rush.rushaicodemother.orchestration.runtime.execution;

/**
 * 当有限的任务预算耗尽时引发。
 */
public final class GenerationBudgetExceededException extends GenerationExecutionPolicyException {

    private final GenerationBudgetKind budgetKind;
    private final int limit;

    public GenerationBudgetExceededException(GenerationBudgetKind budgetKind, int limit) {
        super("生成任务已达到" + budgetKind.displayName() + "上限（" + limit + "）");
        this.budgetKind = budgetKind;
        this.limit = limit;
    }

    public GenerationBudgetKind budgetKind() {
        return budgetKind;
    }

    public int limit() {
        return limit;
    }
}
