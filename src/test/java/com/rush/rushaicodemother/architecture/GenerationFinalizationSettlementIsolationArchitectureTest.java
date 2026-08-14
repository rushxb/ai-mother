package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 约束生成业务终态先提交，模型账本恢复和积分结算在终态后独立重试。 */
class GenerationFinalizationSettlementIsolationArchitectureTest {

    private static final Path JAVA_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother");

    @Test
    void finalizationTransactionsMustNotDependOnCreditSettlement() throws Exception {
        String finalizationTransaction = source(
                "orchestration", "finalization", "GenerationTaskFinalizationTransaction.java");
        String lifecycle = source(
                "orchestration", "lifecycle", "GenerationTaskLifecycleService.java");

        assertFalse(finalizationTransaction.contains("UserCreditService"));
        assertFalse(finalizationTransaction.contains("chargeGenerationTask("));
        assertFalse(lifecycle.contains("UserCreditService"));
        assertFalse(lifecycle.contains("chargeGenerationTask("));
    }

    @Test
    void settlementCoordinatorMustChargeOnlyAfterDiscoveringTerminalTasks() throws Exception {
        String coordinator = source(
                "service", "credit", "GenerationCreditSettlementCoordinator.java");
        String mapper = source("mapper", "UserCreditMapper.java");

        assertTrue(coordinator.contains("findUnsettledTerminalTaskIds"));
        assertTrue(coordinator.indexOf("findUnsettledTerminalTaskIds")
                        < coordinator.indexOf("chargeGenerationTask(taskId)"),
                "积分结算只能消费已经进入业务终态的任务");
        assertTrue(mapper.contains(
                "status IN ('success', 'failed', 'cancelled', 'deadline_exceeded')"),
                "所有业务终态都必须进入独立结算扫描");
    }

    private String source(String... relativePath) throws Exception {
        return Files.readString(JAVA_ROOT.resolve(Path.of("", relativePath)));
    }
}
