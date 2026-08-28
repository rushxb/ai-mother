package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 租户生成容量、预算账本和公平补投的架构门禁。 */
class TenantGenerationQuotaArchitectureTest {

    private static final Path ROOT = Path.of("");
    private static final Path JAVA_ROOT = ROOT.resolve(Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother"));

    @Test
    void migrationAndBaselinesMustProvideTenantBudgetLedgerIdentity() throws Exception {
        String migration = read("sql/migrations/V20260812_3__tenant_generation_quota.sql");
        String createTable = read("sql/create_table.sql");
        String schema = read("sql/schema.sql");

        assertTrue(migration.contains("information_schema.columns"));
        assertTrue(migration.contains("information_schema.statistics"));
        assertFalse(migration.toLowerCase().contains("update user_credit_transaction"),
                "历史流水没有可信租户身份，迁移不得伪造回填");
        assertTrue(createTable.contains("tenantId"));
        assertTrue(createTable.contains("idx_tenant_generation_budget"));
        assertTrue(schema.contains("tenantId"));
        assertTrue(schema.contains("idx_tenant_generation_budget"));
    }

    @Test
    void admissionMustLockTenantBeforeUser() throws Exception {
        String repository = readJava(
                "infrastructure/persistence/task/MyBatisGenerationTaskAdmissionRepository.java");

        assertTrue(repository.indexOf("lockActiveTenantForGenerationAdmission")
                < repository.indexOf("lockActiveUserForGenerationAdmission"));
    }

    @Test
    void monthlyBudgetMustUseImmutableLedgerAndCoverLegacyChargesOnce() throws Exception {
        String mapper = readJava("mapper/GenerationTaskRuntimeMapper.java");
        int start = mapper.indexOf("SELECT COALESCE(SUM(taskUsage), 0)");
        int end = mapper.indexOf("long sumTenantGenerationCreditUsage", start);
        String budgetQuery = mapper.substring(start, end);

        assertTrue(budgetQuery.contains("FROM user_credit_transaction"));
        assertTrue(budgetQuery.contains("GENERATION_RESERVATION"));
        assertTrue(budgetQuery.contains("GENERATION_SETTLEMENT"));
        assertTrue(budgetQuery.contains("GENERATION_CHARGE"));
        assertTrue(budgetQuery.contains("NOT EXISTS"), "兼容扣费必须排除已有预授权的任务");
        assertFalse(budgetQuery.contains("generation_task"), "删除应用不得清空周期预算用量");
    }

    @Test
    void databaseFallbackDispatchMustRotateAcrossTenants() throws Exception {
        String mapper = readJava("mapper/GenerationTaskRuntimeMapper.java");

        assertTrue(mapper.contains("ROW_NUMBER() OVER"));
        assertTrue(mapper.contains("PARTITION BY tenantId"));
        assertTrue(mapper.contains("ORDER BY tenantRank ASC"));
    }

    @Test
    void tenantAdmissionMetricsMustNotExposeHighCardinalityTenantIdentity() throws Exception {
        String metrics = readJava("monitor/GenerationTenantAdmissionMetricsCollector.java");

        assertTrue(metrics.contains(".tag(\"outcome\""));
        assertFalse(metrics.contains(".tag(\"tenantId\""));
        assertFalse(metrics.contains(".tag(\"tenant_id\""));
    }

    private String readJava(String relativePath) throws Exception {
        return read(JAVA_ROOT.resolve(relativePath).toString());
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(ROOT.resolve(relativePath));
    }
}
