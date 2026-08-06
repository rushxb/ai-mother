package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 编排任务诊断快照的原子写入、安全边界和容量配置门禁。 */
class GenerationTaskSnapshotPersistenceArchitectureTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path DAG_SOURCE_ROOT = PROJECT_ROOT.resolve(Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration", "dag"
    ));

    @Test
    void snapshotStoreMustKeepAtomicBoundedAndPathSafePersistencePrimitives() throws IOException {
        String store = Files.readString(DAG_SOURCE_ROOT.resolve("GenerationOrchestrationTaskStore.java"));

        assertFalse(store.contains("FileUtil"), "诊断快照不得绕过 NIO 路径边界和原子写入");
        assertFalse(store.contains("md5Hex"), "请求指纹不得继续使用 MD5");
        assertTrue(store.contains("sha256Hex"));
        assertTrue(store.contains("StandardCopyOption.ATOMIC_MOVE"));
        assertTrue(store.contains("channel.force(true)"));
        assertTrue(store.contains("LinkOption.NOFOLLOW_LINKS"));
        assertTrue(store.contains("getMaxSnapshotBytes()"));
        assertTrue(store.contains("getMaxSnapshotsPerApp()"));
        assertTrue(store.contains("getRetention()"));
    }

    @Test
    void snapshotConfigurationMustRemainValidatedAndExternallyControllable() throws IOException {
        String properties = Files.readString(DAG_SOURCE_ROOT.resolve("GenerationTaskSnapshotProperties.java"));
        String yaml = Files.readString(PROJECT_ROOT.resolve(Path.of("src", "main", "resources", "application.yml")));

        assertTrue(properties.contains("@ConfigurationProperties(prefix = \"app.generation-task-snapshot\")"));
        assertTrue(properties.contains("@Validated"));
        assertTrue(properties.contains("@Min(MIN_SNAPSHOT_BYTES)"));
        assertTrue(properties.contains("@Max(MAX_SNAPSHOT_BYTES)"));

        // 只有开关和存储根仍可按部署环境覆盖，其余资源上限已下沉为常量。
        assertTrue(yaml.contains("GENERATION_TASK_SNAPSHOT_ENABLED"));
        assertTrue(yaml.contains("GENERATION_TASK_SNAPSHOT_ROOT_DIRECTORY"));
        assertFalse(yaml.contains("GENERATION_TASK_SNAPSHOT_MAX_BYTES"));
        assertFalse(yaml.contains("GENERATION_TASK_SNAPSHOT_MAX_PER_APP"));
        assertFalse(yaml.contains("GENERATION_TASK_SNAPSHOT_RETENTION"));
        assertFalse(yaml.contains("GENERATION_TASK_SNAPSHOT_LOCK_STRIPES"));

        // 资源上限必须仍以常量形式声明，保证策略可审计。
        assertTrue(properties.contains("public static final int DEFAULT_MAX_SNAPSHOT_BYTES"));
        assertTrue(properties.contains("public static final int MAX_SNAPSHOTS_PER_APP"));
        assertTrue(properties.contains("public static final Duration RETENTION"));
        assertTrue(properties.contains("public static final int LOCK_STRIPES"));
    }
}
