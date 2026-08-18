package com.rush.rushaicodemother.core.saver;

import com.rush.rushaicodemother.ai.model.HtmlCodeResult;
import com.rush.rushaicodemother.ai.model.MultiFileCodeResult;
import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.security.workspace.GeneratedWorkspaceTrustPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeFileSaverExecutorTest {

    private Path testRoot;
    private CodeFileSaverExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        testRoot = Path.of("target", "test-work", "code-file-saver", UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(testRoot);

        CodeStorageProperties storageProperties = new CodeStorageProperties();
        storageProperties.setOutputRootDir(testRoot.resolve("output"));
        storageProperties.setDeployRootDir(testRoot.resolve("deploy"));
        GenerationWorkspaceService workspaceService = new GenerationWorkspaceService(storageProperties);
        WorkspaceFileSystemService fileSystemService =
                new WorkspaceFileSystemService(new WorkspaceFileSystemProperties());

        executor = new CodeFileSaverExecutor(List.of(
                new HtmlCodeFileSaverTemplate(
                        workspaceService, fileSystemService, new GeneratedWorkspaceTrustPolicy()),
                new MultiFileCodeFileSaverTemplate(
                        workspaceService, fileSystemService, new GeneratedWorkspaceTrustPolicy())
        ));
    }

    @Test
    void savesHtmlIntoCanonicalConfiguredWorkspace() throws IOException {
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode("<main>production-safe</main>");

        File savedDirectory = executor.executeSaver(result, CodeGenTypeEnum.HTML, 101L);

        Path expectedRoot = testRoot.resolve("output").resolve("html_101").toAbsolutePath().normalize();
        assertEquals(expectedRoot, savedDirectory.toPath().toAbsolutePath().normalize());
        assertEquals(
                "<main>production-safe</main>",
                Files.readString(expectedRoot.resolve("index.html"))
        );
    }

    @Test
    void savesOnlyPresentOptionalMultiFileContent() throws IOException {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode("<main>multi-file</main>");
        result.setCssCode("body { color: red; }");
        result.setJsCode("console.log('ready');");

        File savedDirectory = executor.executeSaver(result, CodeGenTypeEnum.MULTI_FILE, 102L);
        Path root = savedDirectory.toPath();
        assertTrue(Files.exists(root.resolve("style.css")));

        result.setCssCode(" ");
        executor.executeSaver(result, CodeGenTypeEnum.MULTI_FILE, 102L);

        assertEquals("<main>multi-file</main>", Files.readString(root.resolve("index.html")));
        assertFalse(Files.exists(root.resolve("style.css")));
        assertEquals("console.log('ready');", Files.readString(root.resolve("script.js")));
    }

    @Test
    void rejectsMismatchedResultTypeBeforeCreatingWorkspace() {
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode("<main>wrong route</main>");

        assertThrows(
                BusinessException.class,
                () -> executor.executeSaver(result, CodeGenTypeEnum.MULTI_FILE, 103L)
        );
        assertFalse(Files.exists(testRoot.resolve("output").resolve("multi_file_103")));
    }

    @Test
    void rejectsUnsupportedGenerationType() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> executor.executeSaver(new Object(), CodeGenTypeEnum.VUE_PROJECT, 104L)
        );

        assertTrue(exception.getMessage().contains("不支持的代码生成类型"));
    }

    @Test
    void rejectsUntrustedGeneratedManifestBeforeCreatingWorkspace() {
        CodeStorageProperties storageProperties = new CodeStorageProperties();
        storageProperties.setOutputRootDir(testRoot.resolve("untrusted-output"));
        storageProperties.setDeployRootDir(testRoot.resolve("untrusted-deploy"));
        GenerationWorkspaceService workspaceService = new GenerationWorkspaceService(storageProperties);
        WorkspaceFileSystemService fileSystemService =
                new WorkspaceFileSystemService(new WorkspaceFileSystemProperties());
        CodeFileSaverTemplate<String> unsafeSaver = new CodeFileSaverTemplate<>(
                String.class, workspaceService, fileSystemService,
                new GeneratedWorkspaceTrustPolicy()) {
            @Override
            protected List<GeneratedCodeFile> generatedFiles(String result) {
                return List.of(
                        new GeneratedCodeFile("index.html", "<main>must-not-be-written</main>"),
                        new GeneratedCodeFile("package.json", result)
                );
            }

            @Override
            protected CodeGenTypeEnum getCodeType() {
                return CodeGenTypeEnum.HTML;
            }
        };
        String untrustedManifest = """
                {
                  "scripts": {
                    "postinstall": "node steal-secrets.js"
                  }
                }
                """;

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> unsafeSaver.saveCode(untrustedManifest, 105L));

        assertTrue(exception.getMessage().contains(
                "executable_manifest_forbidden_lifecycle:postinstall"));
        assertFalse(Files.exists(testRoot.resolve("untrusted-output").resolve("html_105")));
    }

    @Test
    void rejectsDuplicateSaverRegistration() {
        CodeStorageProperties storageProperties = new CodeStorageProperties();
        storageProperties.setOutputRootDir(testRoot.resolve("other-output"));
        storageProperties.setDeployRootDir(testRoot.resolve("other-deploy"));
        GenerationWorkspaceService workspaceService = new GenerationWorkspaceService(storageProperties);
        WorkspaceFileSystemService fileSystemService =
                new WorkspaceFileSystemService(new WorkspaceFileSystemProperties());
        HtmlCodeFileSaverTemplate saver = new HtmlCodeFileSaverTemplate(
                workspaceService, fileSystemService, new GeneratedWorkspaceTrustPolicy());

        assertThrows(IllegalStateException.class, () -> new CodeFileSaverExecutor(List.of(saver, saver)));
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
