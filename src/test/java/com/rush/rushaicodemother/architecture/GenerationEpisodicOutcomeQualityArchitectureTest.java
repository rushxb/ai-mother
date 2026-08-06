package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 情景记录（结果质量字段）的发布门禁。
 *
 * <p>核心不变量：这 8 个字段只有一个写入所有者，且写入必须用 {@code COALESCE} 保护 ——
 * 传 {@code null} 表示「未采集」而不是「清空」。一旦有人改成直接赋值，重试就会擦掉先前
 * 已采集的归因数据，而这种数据损坏在运行时不会报错。</p>
 */
class GenerationEpisodicOutcomeQualityArchitectureTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path JAVA_ROOT = ROOT.resolve(Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother"));
    private static final Path MIGRATION = ROOT.resolve(Path.of(
            "sql", "migrations", "V20260804_1__generation_episodic_outcome_quality.sql"));

    private static final List<String> OUTCOME_QUALITY_COLUMNS = List.of(
            "thinkingMode", "changedFileCount", "firstBuildPassed", "repairRounds",
            "firstPreviewMillis", "failureCategory", "reworkedAt", "distilledAt");

    @Test
    void migrationAndBaselineMustDeclareEveryOutcomeQualityColumn() throws IOException {
        String migration = Files.readString(MIGRATION);
        String baseline = Files.readString(ROOT.resolve(Path.of("sql", "create_table.sql")));

        for (String column : OUTCOME_QUALITY_COLUMNS) {
            assertTrue(migration.contains(column), "迁移缺少结果质量列: " + column);
            assertTrue(baseline.contains(column), "基线缺少结果质量列: " + column);
        }
        assertTrue(migration.contains("idx_generation_task_distill_claim"));
        assertTrue(baseline.contains("idx_generation_task_distill_claim"));
        assertTrue(migration.contains("chk_generation_task_outcome_quality"));
        assertTrue(baseline.contains("chk_generation_task_outcome_quality"));
    }

    @Test
    void migrationMustRemainIdempotentAndAvoidBackfillingBusinessData() throws IOException {
        String migration = Files.readString(MIGRATION);

        // 与既有迁移一致：用 information_schema 守卫，重复执行安全。
        assertTrue(migration.contains("information_schema.columns"));
        assertTrue(migration.contains("information_schema.statistics"));
        assertTrue(migration.contains("information_schema.table_constraints"));
        // 这些列语义是「未采集」，不得用 UPDATE 回填默认值伪造历史归因数据。
        assertFalse(migration.toUpperCase().contains("UPDATE GENERATION_TASK SET"));
    }

    @Test
    void boundaryConstraintsMustAllowUncollectedButRejectInvalidValues() throws IOException {
        String migration = Files.readString(MIGRATION);

        for (String constraint : new String[]{
                "changedFileCount IS NULL OR changedFileCount >= 0",
                "repairRounds IS NULL OR repairRounds >= 0",
                "firstPreviewMillis IS NULL OR firstPreviewMillis >= 0",
                "firstBuildPassed IS NULL OR firstBuildPassed IN (0, 1)"
        }) {
            assertTrue(migration.contains(constraint), "缺少边界约束: " + constraint);
        }
    }

    @Test
    void terminalUpdateMustProtectCollectedValuesWithCoalesce() throws IOException {
        String mapper = Files.readString(JAVA_ROOT.resolve(Path.of(
                "mapper", "GenerationTraceMapper.java")));

        for (String column : OUTCOME_QUALITY_COLUMNS) {
            String coalesce = column + " = COALESCE(#{" + column + "}, " + column + ")";
            assertTrue(mapper.contains(coalesce),
                    "结果质量列必须用 COALESCE 保护，否则重试会擦掉已采集值: " + column);
        }
    }

    @Test
    void outcomeQualityColumnsMustHaveExactlyOneWriteOwner() throws IOException {
        // 单一写入所有权：除了终态 UPDATE，不得有第二处 SQL 写这些列。
        String mapperSources = readAllJava(JAVA_ROOT.resolve("mapper"));

        for (String column : OUTCOME_QUALITY_COLUMNS) {
            long writeSites = mapperSources.lines()
                    .filter(line -> line.contains(column + " = COALESCE(#{")
                            || line.contains(column + " = #{"))
                    .count();
            assertEquals(1L, writeSites,
                    "结果质量列必须只有一个写入点: " + column + "，实际 " + writeSites + " 处");
        }
    }

    @Test
    void episodicRecordMustNotIntroduceSeparateTableOrOutbox() throws IOException {
        String migration = Files.readString(MIGRATION);

        // 设计上明确复用 generation_task 与既有 outbox 租约，不新建表、不新写一套 outbox。
        assertFalse(migration.toLowerCase().contains("create table"));
        assertFalse(migration.contains("generation_episode"));
        try (var migrations = Files.list(ROOT.resolve(Path.of("sql", "migrations")))) {
            assertTrue(migrations.noneMatch(path ->
                            path.getFileName().toString().contains("generation_episode")),
                    "不得新建独立的情景记录表迁移");
        }
    }

    private String readAllJava(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (IOException failure) {
                            throw new IllegalStateException("无法读取源文件: " + path, failure);
                        }
                    })
                    .reduce("", (left, right) -> left + "\n" + right);
        }
    }
}
