package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationScenarioAttributionArchitectureTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path MIGRATION = ROOT.resolve(Path.of(
            "sql", "migrations", "V20260812_2__generation_scenario_attribution.sql"));
    private static final List<String> COLUMNS = List.of(
            "intentSignature", "intentProfileVersion", "routeDecisionVersion",
            "routeEvidenceJson", "routeAlternativesJson", "routeReleaseIdentity");

    @Test
    void migrationAndBaselinesMustDeclareScenarioFactsAndAttributionIndex() throws IOException {
        String migration = Files.readString(MIGRATION);
        String createTable = Files.readString(ROOT.resolve(Path.of("sql", "create_table.sql")));
        String schema = Files.readString(ROOT.resolve(Path.of("sql", "schema.sql")));

        for (String column : COLUMNS) {
            assertTrue(migration.contains(column), "迁移缺少场景事实列: " + column);
            assertTrue(createTable.contains(column), "建表基线缺少场景事实列: " + column);
            assertTrue(schema.contains(column), "生产基线缺少场景事实列: " + column);
        }
        assertTrue(migration.contains("idx_generation_task_scenario_attribution"));
        assertTrue(migration.contains("intentSignature, endTime, route, status, id"));
        assertTrue(createTable.contains("idx_generation_task_scenario_attribution"));
        assertTrue(schema.contains("idx_generation_task_scenario_attribution"));
    }

    @Test
    void migrationMustRemainIdempotentWithoutFabricatingHistoricalFacts() throws IOException {
        String migration = Files.readString(MIGRATION);

        assertTrue(migration.contains("information_schema.columns"));
        assertTrue(migration.contains("information_schema.statistics"));
        assertFalse(migration.toUpperCase().contains("UPDATE GENERATION_TASK SET"));
        assertFalse(migration.toLowerCase().contains("create table"));
    }

    @Test
    void readQueryMustFilterCallsAndKeepQualityLatencyCostObservationsSeparate() throws IOException {
        String mapper = Files.readString(ROOT.resolve(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "mapper", "GenerationScenarioAttributionMapper.java")));

        assertTrue(mapper.contains("FROM generation_model_call\n                WHERE taskId = #{taskId}"));
        assertTrue(mapper.contains("task.intentSignature = #{intentSignature}"));
        assertTrue(mapper.contains("task.routeDecisionVersion, task.route, task.routeReleaseIdentity"));
        assertTrue(mapper.contains("CUME_DIST() OVER"));
        assertTrue(mapper.contains("AS p95FirstUsefulMs"));
        assertTrue(mapper.contains("AS p95DeliveredMs"));
        assertTrue(mapper.contains("AS validationObservedCount"));
        assertTrue(mapper.contains("AS repairObservedCount"));
        assertTrue(mapper.contains("AS providerCostObservedCount"));
        assertTrue(mapper.contains("AS creditCostObservedCount"));
        assertTrue(mapper.contains("FROM generation_model_call\n                    WHERE isDelete = 0"));
        assertTrue(mapper.contains("COUNT(*) AS physicalCallCount"));
        assertTrue(mapper.contains("AS terminalCallCount"));
        assertTrue(mapper.contains("invocationPurpose = 'GENERATION'"));
        assertTrue(mapper.contains(
                "SUM(CASE WHEN totalTokens IS NOT NULL THEN 1 ELSE 0 END) AS costObservedCallCount"));
        assertTrue(mapper.contains("COALESCE(physicalCallCount, 0)"));
        assertTrue(mapper.contains("= COALESCE(costObservedCallCount, 0)"));
        assertTrue(mapper.contains("AS capacityObservedTaskCount"));
        assertTrue(mapper.contains("AS totalPhysicalModelCalls"));
        assertTrue(mapper.contains("AS maximumPhysicalModelCallsPerTask"));
        assertTrue(mapper.contains("AS capacityFailureCount"));
        assertFalse(mapper.contains("COUNT(totalTokens) AS providerCostObservedCount"));
        assertTrue(mapper.contains(
                "GROUP BY intentSignature, profileVersion, decisionVersion, route, releaseIdentity"));
    }

    @Test
    void fallbackMustAtomicallyRebindEveryEffectiveAttributionFieldBehindTheFence() throws IOException {
        String mapper = Files.readString(ROOT.resolve(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "mapper", "GenerationTaskRuntimeMapper.java")));

        assertTrue(mapper.contains("int rebindEffectiveTaskCommand("));
        for (String assignment : List.of(
                "route = #{task.route}",
                "routeDecisionVersion = #{task.routeDecisionVersion}",
                "routeEvidenceJson = #{task.routeEvidenceJson}",
                "routeAlternativesJson = #{task.routeAlternativesJson}",
                "routeReleaseIdentity = #{task.routeReleaseIdentity}",
                "runtimePayloadJson = #{task.runtimePayloadJson}")) {
            assertTrue(mapper.contains(assignment), () -> "fallback 缺少原子重绑字段: " + assignment);
        }
        assertTrue(mapper.contains("AND leaseOwner = #{task.leaseOwner}"));
        assertTrue(mapper.contains("AND executionEpoch = #{task.executionEpoch}"));
        assertTrue(mapper.contains("AND version = #{expectedVersion}"));
        assertTrue(mapper.contains("AND terminalIntentSchemaVersion IS NULL"));
    }
}
