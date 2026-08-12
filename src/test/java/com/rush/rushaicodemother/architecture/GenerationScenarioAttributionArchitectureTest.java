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
    void readQueryMustFilterModelCallsEarlyAndSeparateDecisionVersions() throws IOException {
        String mapper = Files.readString(ROOT.resolve(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "mapper", "GenerationScenarioAttributionMapper.java")));

        assertTrue(mapper.contains("FROM generation_model_call\n                WHERE taskId = #{taskId}"));
        assertTrue(mapper.contains("task.intentSignature = #{intentSignature}"));
        assertTrue(mapper.contains("GROUP BY task.intentSignature, task.intentProfileVersion"));
        assertTrue(mapper.contains("task.routeDecisionVersion, task.route, task.routeReleaseIdentity"));
    }
}
