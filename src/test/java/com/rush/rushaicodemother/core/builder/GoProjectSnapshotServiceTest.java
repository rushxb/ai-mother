package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GoProjectSnapshotServiceTest {

    @TempDir
    Path projectRoot;

    @Test
    void shouldFingerprintSourceDependenciesAndEmbeddedAssets() throws Exception {
        GoProjectSnapshotService service = serviceWithDefaults();
        write("go.mod", "module example");
        write("go.sum", "");
        write("main.go", "package main\n//go:embed public/message.txt\nvar message string");
        write("public/message.txt", "one");
        GoProjectSnapshot initial = service.capture(projectRoot);

        write("public/message.txt", "two");
        GoProjectSnapshot assetChanged = service.capture(projectRoot);
        assertNotEquals(initial, assetChanged);

        write("main.go", "package main\nfunc main() {}");
        GoProjectSnapshot sourceChanged = service.capture(projectRoot);
        assertNotEquals(assetChanged, sourceChanged);

        write("go.mod", "module example\n\ngo 1.24");
        GoProjectSnapshot dependencyChanged = service.capture(projectRoot);
        assertNotEquals(sourceChanged, dependencyChanged);
    }

    @Test
    void shouldIgnoreVersionControlMetadata() throws Exception {
        GoProjectSnapshotService service = serviceWithDefaults();
        write("go.mod", "module example");
        write("go.sum", "");
        write("main.go", "package main");
        write(".git/HEAD", "ref: refs/heads/main");
        GoProjectSnapshot initial = service.capture(projectRoot);

        write(".git/HEAD", "ref: refs/heads/other");

        assertEquals(initial, service.capture(projectRoot));
    }

    @Test
    void shouldRejectProjectsBeyondConfiguredResourceLimits() throws Exception {
        WorkspaceFileSystemProperties properties = new WorkspaceFileSystemProperties();
        properties.setMaxFiles(2);
        properties.setMaxFileBytes(4);
        GoProjectSnapshotService service = new GoProjectSnapshotService(properties);
        write("go.mod", "mod");
        write("go.sum", "sum");
        write("main.go", "go");

        IOException fileCountFailure = assertThrows(
                IOException.class,
                () -> service.capture(projectRoot)
        );
        assertEquals("Go 项目文件数量超过快照上限: 2", fileCountFailure.getMessage());

        properties.setMaxFiles(10);
        write("large.txt", "12345");
        IOException fileSizeFailure = assertThrows(
                IOException.class,
                () -> service.capture(projectRoot)
        );
        assertEquals("Go 项目文件超过快照大小上限: large.txt", fileSizeFailure.getMessage());
    }

    @Test
    void shouldRejectSymbolicLinksInsteadOfProducingUnsafeCacheKey() throws Exception {
        GoProjectSnapshotService service = serviceWithDefaults();
        write("go.mod", "module example");
        Path outsideFile = projectRoot.getParent().resolve("outside-go-source.txt");
        Files.writeString(outsideFile, "outside", StandardCharsets.UTF_8);
        Path link = projectRoot.resolve("linked.txt");
        try {
            Files.createSymbolicLink(link, outsideFile);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "当前平台不支持创建符号链接: " + exception.getMessage());
        }

        IOException failure = assertThrows(IOException.class, () -> service.capture(projectRoot));

        assertEquals("Go 项目包含不允许参与缓存的符号链接文件: linked.txt", failure.getMessage());
    }

    @Test
    void shouldStopSnapshotScanWhenContinuationCheckRejectsWork() throws Exception {
        GoProjectSnapshotService service = serviceWithDefaults();
        write("go.mod", "module example");
        write("go.sum", "");
        write("main.go", "package main");
        AtomicInteger checks = new AtomicInteger();

        assertThrows(
                IllegalStateException.class,
                () -> service.capture(projectRoot, () -> {
                    if (checks.incrementAndGet() >= 3) {
                        throw new IllegalStateException("快照扫描已取消");
                    }
                })
        );

        assertEquals(3, checks.get());
    }

    private GoProjectSnapshotService serviceWithDefaults() {
        return new GoProjectSnapshotService(new WorkspaceFileSystemProperties());
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = projectRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
