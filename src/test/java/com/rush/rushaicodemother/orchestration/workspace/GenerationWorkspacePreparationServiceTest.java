package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationWorkspacePreparationServiceTest {

    private Path testRoot;
    private GenerationWorkspaceService service;

    @BeforeEach
    void setUp() throws IOException {
        testRoot = Path.of("target", "test-work", "generation-workspace-prepare", UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(testRoot);
        CodeStorageProperties properties = new CodeStorageProperties();
        properties.setOutputRootDir(testRoot.resolve("output"));
        properties.setDeployRootDir(testRoot.resolve("deploy"));
        service = new GenerationWorkspaceService(properties);
    }

    @Test
    void preparesCanonicalWorkspaceBelowConfiguredOutputRoot() {
        GenerationWorkspace workspace = service.prepare(201L, CodeGenTypeEnum.HTML);

        assertEquals(testRoot.resolve("output").resolve("html_201"), workspace.canonicalRootPath());
        assertTrue(workspace.exists());
        assertTrue(Files.isDirectory(workspace.canonicalRootPath()));
    }

    @Test
    void rejectsExistingNonDirectoryWorkspaceEntry() throws IOException {
        Path outputRoot = testRoot.resolve("output");
        Files.createDirectories(outputRoot);
        Files.writeString(outputRoot.resolve("html_202"), "not-a-directory");

        assertThrows(
                BusinessException.class,
                () -> service.prepare(202L, CodeGenTypeEnum.HTML)
        );
    }

    @Test
    void concurrentPreparationConvergesOnOneValidatedWorkspace() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<GenerationWorkspace>> tasks = java.util.stream.IntStream.range(0, 12)
                    .mapToObj(index -> (Callable<GenerationWorkspace>) () ->
                            service.prepare(203L, CodeGenTypeEnum.MULTI_FILE))
                    .toList();
            List<Future<GenerationWorkspace>> futures = executor.invokeAll(tasks);
            Path expectedRoot = testRoot.resolve("output").resolve("multi_file_203");

            for (Future<GenerationWorkspace> future : futures) {
                assertEquals(expectedRoot, future.get().canonicalRootPath());
                assertTrue(future.get().exists());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsSymbolicLinkWorkspaceRoot() throws IOException {
        Path outputRoot = testRoot.resolve("output");
        Path realDirectory = testRoot.resolve("real-workspace");
        Path symbolicLink = outputRoot.resolve("html_204");
        Files.createDirectories(outputRoot);
        Files.createDirectories(realDirectory);
        try {
            Files.createSymbolicLink(symbolicLink, realDirectory);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.abort("当前平台不允许创建符号链接: " + exception.getMessage());
        }

        assertThrows(
                BusinessException.class,
                () -> service.prepare(204L, CodeGenTypeEnum.HTML)
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        if (testRoot == null || !Files.exists(testRoot)) {
            return;
        }
        Files.walkFileTree(testRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
