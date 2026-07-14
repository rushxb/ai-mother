package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 生成状态数据库所有权、显式 SQL 和升级迁移的架构门禁。 */
class GenerationAppStatePersistenceBoundaryArchitectureTest {

    private static final Path JAVA_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother"
    );

    @Test
    void generationStateServiceMustUseExplicitOwnedWritesInsteadOfGenericMapperUpdate()
            throws IOException {
        String service = Files.readString(JAVA_ROOT.resolve(Path.of(
                "orchestration", "GenerationAppStateService.java")));
        String mapper = Files.readString(JAVA_ROOT.resolve(Path.of("mapper", "AppMapper.java")));

        assertFalse(service.contains("appMapper.update("));
        assertTrue(service.contains("claimGenerationState"));
        assertTrue(service.contains("updateOwnedGenerationStage"));
        assertTrue(service.contains("updateOwnedGenerationSnapshot"));
        assertTrue(service.contains("releaseOwnedGenerationState"));

        assertTrue(mapper.contains("generatingTaskId = #{taskId}"));
        assertTrue(mapper.contains("generationLeaseUntil = #{leaseUntil}"));
        assertTrue(mapper.contains("updateOwnedCodeGenType"));
        assertTrue(mapper.contains("generationLeaseUntil &lt;= #{now}"));
        assertTrue(mapper.contains("isDelete = 0"));
    }

    @Test
    void appSchemaAndMigrationMustPersistGenerationOwnershipAndLease() throws IOException {
        String schema = Files.readString(Path.of("sql", "create_table.sql"));
        Path migrationPath = Path.of(
                "sql", "migrations", "V20260714_4__app_generation_state_ownership.sql");

        assertTrue(Files.exists(migrationPath), "应用生成状态必须提供既有数据库升级迁移");
        String migration = Files.readString(migrationPath);

        assertTrue(schema.contains("generatingTaskId"));
        assertTrue(schema.contains("generationLeaseUntil"));
        assertTrue(schema.contains("idx_generation_lease"));
        assertTrue(schema.contains("chk_app_generation_state_ownership"));
        assertTrue(migration.contains("ADD COLUMN generatingTaskId"));
        assertTrue(migration.contains("ADD COLUMN generationLeaseUntil"));
        assertTrue(migration.contains("idx_generation_lease"));
        assertTrue(migration.contains("chk_app_generation_state_ownership"));
    }
}
