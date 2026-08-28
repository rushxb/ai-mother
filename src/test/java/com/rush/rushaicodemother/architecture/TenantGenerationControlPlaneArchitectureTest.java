package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 租户生成控制面的授权顺序、统计来源和低敏边界门禁。 */
class TenantGenerationControlPlaneArchitectureTest {

    private static final Path JAVA_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother");

    @Test
    void authorizationMustPrecedeEveryTenantWideAggregation() throws Exception {
        String service = readJava(
                "orchestration/governance/TenantGenerationControlPlaneService.java");

        assertTrue(service.indexOf("requireRole(") < service.indexOf("repository.load("));
        assertTrue(service.contains("TenantRole.ADMIN"));
    }

    @Test
    void unitSuccessCostMustIncludeAllSettledAttemptCostsAndUseLowCardinalityDimensions()
            throws Exception {
        String mapper = readJava("mapper/TenantGenerationControlPlaneMapper.java");
        int start = mapper.indexOf("selectScenarioCosts");
        String scenarioQuery = mapper.substring(0, start);

        assertTrue(scenarioQuery.contains(
                "SUM(CASE WHEN task.status = 'success' THEN 1 ELSE 0 END)"));
        assertTrue(scenarioQuery.contains("COUNT(*) AS settledTasks"));
        assertTrue(scenarioQuery.contains("task.creditCharged = 1"));
        assertTrue(scenarioQuery.contains("task.creditCost IS NOT NULL"));
        assertTrue(scenarioQuery.contains("task.endTime >= #{periodStart}"));
        assertTrue(scenarioQuery.contains("task.endTime < #{observedBefore}"));
        assertTrue(scenarioQuery.contains("task.route"));
        assertTrue(scenarioQuery.contains("task.targetCodeGenType"));
        assertTrue(scenarioQuery.contains("HAVING SUM"));
        assertFalse(scenarioQuery.contains("taskId"));
        assertFalse(scenarioQuery.contains("userId"));
        assertFalse(mapper.toLowerCase().contains("rejection_count"),
                "未持久化历史拒绝事实前不得伪造拒绝次数");
    }

    @Test
    void monthlyBudgetMustReuseTheAdmissionLedgerProjection() throws Exception {
        String repository = readJava(
                "infrastructure/persistence/governance/"
                        + "MyBatisTenantGenerationControlPlaneRepository.java");
        String mapper = readJava("mapper/TenantGenerationControlPlaneMapper.java");

        assertTrue(repository.contains("runtimeMapper.sumTenantGenerationCreditUsage("));
        assertFalse(mapper.contains("user_credit_transaction"),
                "控制面不得复制一份可能与准入漂移的月预算 SQL");
    }

    private String readJava(String relativePath) throws Exception {
        return Files.readString(JAVA_ROOT.resolve(relativePath));
    }
}
