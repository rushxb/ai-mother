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
    void routeLatencySegmentModuleMustDependOnPortsAndReuseExistingBoundedQueries() throws IOException {
        String segmentService = Files.readString(JAVA_ROOT.resolve(Path.of(
                "monitor", "latency", "GenerationRouteLatencySegmentService.java")));
        String segmentEnum = Files.readString(JAVA_ROOT.resolve(Path.of(
                "monitor", "latency", "GenerationLatencySegment.java")));

        // 分段画像只做纯计算：不得触碰 mapper 或持久化适配器，必须走既有 port。
        assertFalse(segmentService.contains(".mapper."));
        assertFalse(segmentService.contains("infrastructure.persistence"));
        assertTrue(segmentService.contains("GenerationDurationSampleRepository"));
        assertTrue(segmentService.contains("loadRecentSuccessfulSamples"));
        // 不得新建线程池；缓存与样本上限复用既有进度配置。
        assertFalse(segmentService.contains("Executors."));
        assertTrue(segmentService.contains("GenerationTaskProgressProperties"));
        // 分段定义属于领域判断，不得下沉到持久化层。
        assertFalse(segmentEnum.contains(".mapper."));
        assertFalse(segmentEnum.contains("infrastructure.persistence"));
    }

    @Test
    void etaControlsMustRemainDeclaredAsAuditableConstantsWithoutProductionSecrets() throws IOException {
        String yaml = Files.readString(ROOT.resolve(Path.of("src", "main", "resources", "application.yml")));
        String properties = Files.readString(ROOT.resolve(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration",
                "runtime", "task", "progress", "GenerationTaskProgressProperties.java")));

        // 进度估算参数属于内部算法口径，已整体下沉为常量，不再出现在 yaml 中。
        assertFalse(yaml.contains("generation-progress:"));
        assertFalse(yaml.contains("GENERATION_PROGRESS_TASK_SAMPLE_LIMIT"));
        assertFalse(yaml.contains("GENERATION_PROGRESS_PROFILE_CACHE_TTL"));

        assertTrue(properties.contains("public static final int TASK_SAMPLE_LIMIT"));
        assertTrue(properties.contains("public static final Duration PROFILE_CACHE_TTL"));
        assertTrue(properties.contains("public static final Duration FALLBACK_TOTAL_DURATION"));
        assertTrue(properties.contains("public static final Duration MAXIMUM_ESTIMATED_DURATION"));
        assertTrue(properties.contains("@Validated"));
    }
}
