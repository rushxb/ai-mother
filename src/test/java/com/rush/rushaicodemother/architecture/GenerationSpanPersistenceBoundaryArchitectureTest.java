package com.rush.rushaicodemother.architecture;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.mapper.GenerationTaskSpanMapper;
import com.rush.rushaicodemother.model.entity.GenerationTaskSpan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Durable generation span 的 observer port、显式 SQL 与数据库契约门禁。 */
class GenerationSpanPersistenceBoundaryArchitectureTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path JAVA_ROOT = PROJECT_ROOT.resolve(Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother"));

    @Test
    void monitorDomainMustNotDependOnMapperEntitiesOrPersistenceAdapters() throws IOException {
        Path monitorRoot = JAVA_ROOT.resolve("monitor");
        String sources;
        try (Stream<Path> paths = Files.walk(monitorRoot)) {
            sources = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(this::readUnchecked)
                    .reduce("", (left, right) -> left + "\n" + right);
        }

        assertFalse(sources.contains("com.rush.rushaicodemother.mapper"));
        assertFalse(sources.contains("com.rush.rushaicodemother.model.entity"));
        assertFalse(sources.contains("com.rush.rushaicodemother.infrastructure.persistence"));
    }

    @Test
    void mapperMustUseExplicitAndIdempotentSql() throws IOException {
        assertFalse(BaseMapper.class.isAssignableFrom(GenerationTaskSpanMapper.class));
        String mapper = readJava("mapper", "GenerationTaskSpanMapper.java");

        assertTrue(mapper.contains("INSERT INTO generation_task_span"));
        assertTrue(mapper.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(mapper.contains("ORDER BY startedAt ASC, id ASC"));
        assertFalse(mapper.contains("BaseMapper<"));
        assertFalse(mapper.contains("QueryWrapper"));
    }

    @Test
    void entityPrimaryKeyMustMatchAutoIncrementSchema() throws NoSuchFieldException {
        Field idField = GenerationTaskSpan.class.getDeclaredField("id");
        Id id = idField.getAnnotation(Id.class);

        assertNotNull(id);
        assertEquals(KeyType.Auto, id.keyType());
    }

    @Test
    void baselineAndMigrationMustKeepSpanFieldsAndAnalyticsIndexes() throws IOException {
        String schema = Files.readString(PROJECT_ROOT.resolve(Path.of("sql", "create_table.sql")));
        Path migrationPath = PROJECT_ROOT.resolve(Path.of(
                "sql", "migrations", "V20260716_2__generation_task_span.sql"));
        assertTrue(Files.exists(migrationPath), "既有数据库必须提供 generation_task_span 迁移");
        String migration = Files.readString(migrationPath);

        for (String token : new String[]{
                "generation_task_span", "spanId", "taskId", "stage", "category",
                "status", "startedAt", "endedAt", "durationMs", "detail",
                "uk_spanId", "idx_task_started", "idx_stage_duration"
        }) {
            assertTrue(schema.contains(token), "基线缺少 span 契约: " + token);
            assertTrue(migration.contains(token), "迁移缺少 span 契约: " + token);
        }
        assertTrue(migration.contains("not a checkpoint or deterministic replay journal"));
    }

    private String readJava(String... path) throws IOException {
        return Files.readString(JAVA_ROOT.resolve(Path.of("", path)));
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 span 架构源码: " + path, exception);
        }
    }
}
