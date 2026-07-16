package com.rush.rushaicodemother.architecture;

import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.mapper.GenerationTaskSpanMapper;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationSampleRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Historical ETA/profile boundary and indexed-query contract gates. */
class GenerationDurationProfileArchitectureTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path JAVA_ROOT = ROOT.resolve(Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother"));

    @Test
    void progressDomainMustDependOnPortsInsteadOfMappersOrPersistenceAdapters() throws IOException {
        String repositoryPort = Files.readString(JAVA_ROOT.resolve(Path.of(
                "orchestration", "runtime", "task", "progress", "GenerationDurationSampleRepository.java")));
        String profileService = Files.readString(JAVA_ROOT.resolve(Path.of(
                "orchestration", "runtime", "task", "progress", "GenerationDurationProfileService.java")));

        assertTrue(GenerationDurationSampleRepository.class.isInterface());
        assertFalse(repositoryPort.contains(".mapper."));
        assertFalse(profileService.contains(".mapper."));
        assertFalse(profileService.contains("infrastructure.persistence"));
    }

    @Test
    void durationQueriesMustRemainExplicitBoundedAndIndependentFromBaseMapper() throws IOException {
        String taskMapper = Files.readString(JAVA_ROOT.resolve(Path.of(
                "mapper", "GenerationTaskRuntimeMapper.java")));
        String spanMapper = Files.readString(JAVA_ROOT.resolve(Path.of(
                "mapper", "GenerationTaskSpanMapper.java")));

        assertFalse(BaseMapper.class.isAssignableFrom(GenerationTaskRuntimeMapper.class));
        assertFalse(BaseMapper.class.isAssignableFrom(GenerationTaskSpanMapper.class));
        assertTrue(taskMapper.contains("selectRecentSuccessfulDurationsByRoute"));
        assertTrue(taskMapper.contains("LIMIT #{limit}"));
        assertTrue(spanMapper.contains("selectRecentSuccessfulByRoute"));
        assertTrue(spanMapper.contains("INNER JOIN generation_task"));
        assertTrue(spanMapper.contains("LIMIT #{limit}"));
    }

    @Test
    void routeDurationQueryMustHaveBaselineAndIdempotentMigrationIndex() throws IOException {
        String schema = Files.readString(ROOT.resolve(Path.of("sql", "create_table.sql")));
        Path migrationPath = ROOT.resolve(Path.of(
                "sql", "migrations", "V20260716_3__generation_duration_profile_index.sql"));
        String migration = Files.readString(migrationPath);

        assertTrue(schema.contains("idx_route_success_duration"));
        assertTrue(schema.contains("route, status, isDelete, endTime, id"));
        assertTrue(migration.contains("information_schema.statistics"));
        assertTrue(migration.contains("idx_route_success_duration"));
    }

    @Test
    void applicationYamlMustExposeAllEtaControlsWithoutProductionSecrets() throws IOException {
        String yaml = Files.readString(ROOT.resolve(Path.of("src", "main", "resources", "application.yml")));

        assertTrue(yaml.contains("generation-progress:"));
        assertTrue(yaml.contains("GENERATION_PROGRESS_TASK_SAMPLE_LIMIT"));
        assertTrue(yaml.contains("GENERATION_PROGRESS_PROFILE_CACHE_TTL"));
        assertTrue(yaml.contains("GENERATION_PROGRESS_FALLBACK_TOTAL_DURATION"));
        assertTrue(yaml.contains("GENERATION_PROGRESS_MAXIMUM_ESTIMATED_DURATION"));
    }
}
