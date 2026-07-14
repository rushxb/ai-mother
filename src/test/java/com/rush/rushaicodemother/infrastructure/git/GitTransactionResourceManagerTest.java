package com.rush.rushaicodemother.infrastructure.git;

import com.rush.rushaicodemother.infrastructure.git.GitTransactionResourceManager.GitTransactionResourceException;
import com.rush.rushaicodemother.infrastructure.git.GitTransactionResourceManager.GitTransactionResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitTransactionResourceManagerTest {

    private final GitTransactionResourceManager resourceManager = new GitTransactionResourceManager();

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldRejectPathspecWhenUtf8PayloadExceedsConfiguredLimit() throws IOException {
        Path gitDirectory = Files.createDirectory(temporaryDirectory.resolve(".git"));

        GitTransactionResourceException exception = assertThrows(
                GitTransactionResourceException.class,
                () -> resourceManager.create(gitDirectory, List.of("abcd"), 4)
        );

        assertEquals(
                GitTransactionResourceException.Reason.PATHSPEC_LIMIT_EXCEEDED,
                exception.reason()
        );
        try (var entries = Files.list(gitDirectory)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    @Test
    void shouldRejectOversizedAndMalformedStagedOutput() throws Exception {
        Path gitDirectory = Files.createDirectory(temporaryDirectory.resolve(".git"));
        GitTransactionResources resources = resourceManager.create(gitDirectory, List.of("src/Main.java"), 1024);
        try {
            Files.write(resources.temporaryStagedOutput(), new byte[]{'a', 'b', 'c', 0});
            GitTransactionResourceException oversized = assertThrows(
                    GitTransactionResourceException.class,
                    () -> resourceManager.readStagedFiles(resources, 3)
            );
            assertEquals(GitTransactionResourceException.Reason.STAGED_OUTPUT_INVALID, oversized.reason());

            Files.write(resources.temporaryStagedOutput(), new byte[]{(byte) 0xC3, 0x28, 0});
            GitTransactionResourceException malformed = assertThrows(
                    GitTransactionResourceException.class,
                    () -> resourceManager.readStagedFiles(resources, 16)
            );
            assertEquals(GitTransactionResourceException.Reason.STAGED_OUTPUT_INVALID, malformed.reason());
        } finally {
            resourceManager.cleanup(resources);
        }
    }

    @Test
    void shouldReadNormalizedNulDelimitedStagedFiles() throws Exception {
        Path gitDirectory = Files.createDirectory(temporaryDirectory.resolve(".git"));
        GitTransactionResources resources = resourceManager.create(gitDirectory, List.of("src/Main.java"), 1024);
        try {
            Files.write(
                    resources.temporaryStagedOutput(),
                    "src\\Main.java\0docs/??.md\0".getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );

            assertEquals(
                    List.of("src/Main.java", "docs/??.md"),
                    resourceManager.readStagedFiles(resources, 1024)
            );
        } finally {
            resourceManager.cleanup(resources);
        }
    }

    @Test
    void shouldDeleteEveryOwnedTransactionResource() throws Exception {
        Path gitDirectory = Files.createDirectory(temporaryDirectory.resolve(".git"));
        GitTransactionResources resources = resourceManager.create(gitDirectory, List.of("src/Main.java"), 1024);
        Files.writeString(resources.temporaryIndex(), "index");
        Files.writeString(resources.temporaryIndexLock(), "lock");
        Files.writeString(resources.temporaryStagedOutput(), "staged");

        resourceManager.cleanup(resources);

        assertFalse(Files.exists(resources.temporaryIndex()));
        assertFalse(Files.exists(resources.temporaryIndexLock()));
        assertFalse(Files.exists(resources.temporaryPathspec()));
        assertFalse(Files.exists(resources.temporaryStagedOutput()));
        assertFalse(Files.exists(resources.temporaryHooksDirectory()));
    }

    @Test
    void cleanupMustNotDeleteMatchingResourceOutsideOwnedGitDirectory() throws Exception {
        Path gitDirectory = Files.createDirectory(temporaryDirectory.resolve(".git"));
        GitTransactionResources resources = resourceManager.create(gitDirectory, List.of("src/Main.java"), 1024);
        Path outsideDirectory = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Path outsideStagedFile = Files.writeString(
                outsideDirectory.resolve("ai-code-mother-attacker.staged"),
                "must-remain"
        );
        GitTransactionResources tamperedResources = new GitTransactionResources(
                resources.gitDirectory(),
                resources.temporaryIndex(),
                resources.temporaryPathspec(),
                outsideStagedFile,
                resources.temporaryHooksDirectory()
        );

        resourceManager.cleanup(tamperedResources);

        assertTrue(Files.exists(outsideStagedFile));
        Files.deleteIfExists(outsideStagedFile);
    }
}
