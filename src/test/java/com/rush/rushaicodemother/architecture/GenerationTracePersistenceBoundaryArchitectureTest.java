package com.rush.rushaicodemother.architecture;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.mapper.GenerationTraceMapper;
import com.rush.rushaicodemother.model.entity.GenerationBuildLog;
import com.rush.rushaicodemother.model.entity.GenerationModelCall;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GenerationTrace 业务边界、显式 SQL 和数据库契约的架构门禁。 */
class GenerationTracePersistenceBoundaryArchitectureTest {

    private static final Path PROJECT_ROOT = Path.of("");
    private static final Path JAVA_ROOT = PROJECT_ROOT.resolve(Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother"
    ));

    @Test
    void businessServiceMustNotLeakMapperEntitiesOrQueryWrappers() throws IOException {
        String serviceSource = readJava("service", "trace", "DefaultGenerationTraceService.java");

        assertFalse(serviceSource.contains("com.rush.rushaicodemother.mapper"));
        assertFalse(serviceSource.contains("com.rush.rushaicodemother.model.entity"));
        assertFalse(serviceSource.contains("QueryWrapper"));
    }

    @Test
    void mapperMustUseExplicitSqlAndConcurrencyGuards() throws IOException {
        assertFalse(BaseMapper.class.isAssignableFrom(GenerationTraceMapper.class));
        String mapperSource = readJava("mapper", "GenerationTraceMapper.java");

        assertTrue(mapperSource.contains("FOR UPDATE"));
        assertTrue(mapperSource.contains("AND status = 'running'"));
        assertTrue(mapperSource.contains("WHERE callId = #{callId}"));
        assertFalse(mapperSource.contains("selectListByQuery"));
    }

    @Test
    void legacyTraceServiceAndGenericMappersMustRemainRemoved() {
        List<Path> removedFiles = List.of(
                JAVA_ROOT.resolve(Path.of("service", "GenerationTraceService.java")),
                JAVA_ROOT.resolve(Path.of("service", "impl", "GenerationTraceServiceImpl.java")),
                JAVA_ROOT.resolve(Path.of("mapper", "GenerationTaskMapper.java")),
                JAVA_ROOT.resolve(Path.of("mapper", "GenerationModelCallMapper.java")),
                JAVA_ROOT.resolve(Path.of("mapper", "GenerationBuildLogMapper.java"))
        );

        assertTrue(removedFiles.stream().noneMatch(Files::exists));
    }

    @Test
    void tracePrimaryKeysMustMatchAutoIncrementSchema() throws NoSuchFieldException {
        for (Class<?> entityType : List.of(
                GenerationTask.class, GenerationModelCall.class, GenerationBuildLog.class)) {
            Field idField = entityType.getDeclaredField("id");
            Id id = idField.getAnnotation(Id.class);
            assertNotNull(id);
            assertEquals(KeyType.Auto, id.keyType());
        }
    }

    @Test
    void schemaMustDeclareStageMessageAndModelCallIdempotency() throws IOException {
        String schema = Files.readString(PROJECT_ROOT.resolve(Path.of("sql", "create_table.sql")));
        String migration = Files.readString(PROJECT_ROOT.resolve(Path.of(
                "sql", "migrations", "V20260714_1__generation_trace_integrity.sql"
        )));

        assertTrue(schema.contains("stageMessage            text"));
        assertTrue(schema.contains("callId           varchar(36)"));
        assertTrue(schema.contains("UNIQUE KEY uk_callId (callId)"));
        assertTrue(schema.contains("deadline_exceeded"));
        assertTrue(migration.contains("ROW_NUMBER() OVER (PARTITION BY callId"));
        assertTrue(migration.contains("MODIFY COLUMN callId varchar(36) not null"));
    }

    private String readJava(String... relativePath) throws IOException {
        return Files.readString(JAVA_ROOT.resolve(Path.of("", relativePath)));
    }
}
