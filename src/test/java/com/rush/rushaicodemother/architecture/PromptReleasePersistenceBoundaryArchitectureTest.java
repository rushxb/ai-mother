package com.rush.rushaicodemother.architecture;

import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.mapper.AiPromptReleaseMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptReleasePersistenceBoundaryArchitectureTest {

    @Test
    void promptReleaseDomainMustDependOnPersistencePortOnly() throws Exception {
        Path root = Path.of("src", "main", "java", "com", "rush", "rushaicodemother",
                "ai", "prompt", "release");
        try (var files = Files.walk(root)) {
            String source = files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(this::read)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertFalse(source.contains("com.rush.rushaicodemother.mapper"));
            assertFalse(source.contains("infrastructure.persistence"));
            assertTrue(source.contains("PromptReleaseRepository"));
        }
    }

    @Test
    void promptReleaseMapperMustUseExplicitLockAndAppendOnlyHistory() throws Exception {
        assertFalse(BaseMapper.class.isAssignableFrom(AiPromptReleaseMapper.class));
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "mapper", "AiPromptReleaseMapper.java"));
        assertTrue(source.contains("WHERE id = 1 FOR UPDATE"));
        assertTrue(source.contains("AND revision = #{expectedRevision}"));
        assertTrue(source.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(source.contains("INSERT INTO ai_prompt_release_history"));
        assertFalse(source.contains("UPDATE ai_prompt_release_history"));
        assertFalse(source.contains("DELETE FROM ai_prompt_release_history"));
    }

    @Test
    void promptReleaseSchemaMustEnforceAtomicHeadCanaryAndAuditShape() throws Exception {
        String schema = Files.readString(Path.of("sql", "create_table.sql"));
        String migration = Files.readString(Path.of(
                "sql", "migrations", "V20260717_6__ai_prompt_release.sql"));
        for (String contract : java.util.List.of(
                "chk_ai_prompt_release_bundle_id",
                "chk_ai_prompt_release_bundle_revision",
                "chk_ai_prompt_release_canary",
                "chk_ai_prompt_release_history_action",
                "chk_ai_prompt_release_history_source",
                "char_length(trim(changeNote)) between 1 and 512",
                "action in ('PUBLISH', 'ROLLBACK')"
        )) {
            assertTrue(schema.contains(contract));
            assertTrue(migration.contains(contract));
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
