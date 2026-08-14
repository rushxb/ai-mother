package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.orchestration.router.GenerationMode;

/** 校验各生成路由的根模型调用预算能覆盖其合法执行拓扑。 */
final class GenerationRootModelBudgetTopology {

    private GenerationRootModelBudgetTopology() {
    }

    /** 返回{@code supports}。 */
    static boolean supports(GenerationMode mode,
                            int maxRootModelAttempts,
                            int maxRepairRounds) {
        if (mode == null || maxRootModelAttempts <= 0 || maxRepairRounds < 0) {
            return false;
        }
        try {
            return maxRootModelAttempts >= minimumRequired(mode, maxRepairRounds);
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    static int minimumRequired(GenerationMode mode, int maxRepairRounds) {
        int callsBeforeRepair = switch (mode) {
            // CREATE 最长合法路径会依次调用规格模型、重型意图路由和首次主生成。
            case CREATE -> 3;
            case READ_ONLY -> 1;
            // HEAVY_EXPERT 在首次主生成前需要一次意图路由模型调用。
            case HEAVY_EXPERT -> 2;
            case LIGHT_EDIT, AGENT_EDIT -> 1;
        };
        return Math.addExact(callsBeforeRepair, maxRepairRounds);
    }
}
