package com.rush.rushaicodemother.orchestration;

/** 代码生成前规划层的消融实验方案。 */
public enum GenerationPlanningVariant {
    NO_PLAN(0),
    COMPACT_PLAN(1),
    CURRENT_DAG(2);

    private final int planningDepth;

    GenerationPlanningVariant(int planningDepth) {
        this.planningDepth = planningDepth;
    }

    public boolean isSimplerThan(GenerationPlanningVariant baseline) {
        return baseline != null && planningDepth < baseline.planningDepth;
    }
}
