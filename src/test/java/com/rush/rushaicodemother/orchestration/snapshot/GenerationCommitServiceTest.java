package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.config.ExternalProcessProperties;
import com.rush.rushaicodemother.config.GenerationCommitProperties;
import com.rush.rushaicodemother.infrastructure.git.GitCommandExecutor;
import com.rush.rushaicodemother.infrastructure.git.GitCommandResult;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationCommitResult;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationCommitServiceTest {

    @Test
    void shouldNotExposeUnexpectedGitExceptionDetails() throws Exception {
        Path tempRoot = cleanTestRoot("exception-sanitization");
        Path outputRoot = tempRoot.resolve("code_output");
        Path projectRoot = Files.createDirectories(outputRoot.resolve("vue_project_26"));
        Files.writeString(projectRoot.resolve("index.html"), "<html></html>\n");
        GitCommandExecutor gitExecutor = mock(GitCommandExecutor.class);
        when(gitExecutor.execute(any(Path.class), anyList(), anyMap(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("provider-api-key=secret-value"));
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()), gitExecutor, outputRoot);

        GenerationCommitResult result = service.commit(
                26L,
                "task-26",
                diffArtifact(26L, projectRoot, "index.html")
        );

        assertEquals("failed", result.status());
        assertEquals("git_commit_exception", result.reason());
        assertFalse(result.toPayload().toString().contains("secret-value"));
    }

    @Test
    void shouldNotSwallowGenerationDeadlineFromGitCommand() throws Exception {
        Path tempRoot = cleanTestRoot("deadline-propagation");
        Path outputRoot = tempRoot.resolve("code_output");
        Path projectRoot = Files.createDirectories(outputRoot.resolve("vue_project_40"));
        Files.writeString(projectRoot.resolve("index.html"), "<html></html>\n");
        GitCommandExecutor gitExecutor = mock(GitCommandExecutor.class);
        when(gitExecutor.execute(any(Path.class), anyList(), anyMap(), anyString(), anyString()))
                .thenThrow(new GenerationDeadlineExceededException("task-40"));
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                gitExecutor,
                outputRoot
        );

        assertThrows(
                GenerationDeadlineExceededException.class,
                () -> service.commit(40L, "task-40", diffArtifact(40L, projectRoot, "index.html"))
        );
    }

    @Test
    void shouldCommitOnlyChangedFilesInsideGitRepository() throws Exception {
        Path tempRoot = cleanTestRoot("commit");
        Path outputRoot = tempRoot.resolve("code_output");
        Path projectRoot = outputRoot.resolve("vue_project_21");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/App.vue"), "<template>old</template>\n");
        Files.writeString(projectRoot.resolve("src/keep.txt"), "skip\n");
        initGitRepository(projectRoot);
        Files.writeString(projectRoot.resolve(".gitignore"), "src/keep.txt\n");
        Files.writeString(projectRoot.resolve("src/pre-staged.txt"), "preserve staged state\n");
        runGit(projectRoot, "add", "src/pre-staged.txt");
        installFailingIfExecutedPreCommitHook(projectRoot);
        Files.writeString(projectRoot.resolve("src/App.vue"), "<template>new</template>\n");

        DiffSummary diffSummary = DiffSummary.created(
                21L,
                "task-21",
                tempRoot.resolve("snapshot").toString(),
                projectRoot.toString(),
                List.of("src/App.vue"),
                List.of(),
                List.of(),
                List.of()
        );
        GenerationArtifact artifact = GenerationArtifact.of("diff_summary", "test", "diff", diffSummary.toPayload());

        GenerationCommitService service = SnapshotServiceTestFixture.commitService(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()), gitCommandExecutor(), outputRoot);
        GenerationCommitResult result = service.commit(21L, "task-21", artifact);

        assertEquals("committed", result.status());
        assertTrue(result.commitId().length() >= 7);
        assertEquals(List.of("src/App.vue"), result.committedFiles());
        assertEquals("local_git", result.provider());
        assertEquals(
                List.of("src/pre-staged.txt"),
                nonBlankLines(runGitForOutput(projectRoot, "diff", "--cached", "--name-only"))
        );
        assertEquals(
                List.of("src/App.vue"),
                nonBlankLines(runGitForOutput(projectRoot, "show", "--pretty=", "--name-only", "HEAD"))
        );
        assertTrue(Files.notExists(projectRoot.resolve("hook-ran.txt")));
        try (var gitFiles = Files.list(projectRoot.resolve(".git"))) {
            assertTrue(gitFiles.noneMatch(path -> path.getFileName().toString().startsWith("ai-code-mother-")));
        }
    }

    @Test
    void shouldTreatGeneratedFileNamesAsLiteralGitPathspecs() throws Exception {
        Path tempRoot = cleanTestRoot("literal-pathspec");
        Path outputRoot = tempRoot.resolve("code_output");
        Path projectRoot = outputRoot.resolve("vue_project_23");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/[abc].txt"), "old\n");
        Files.writeString(projectRoot.resolve("src/a.txt"), "unchanged\n");
        initGitRepository(projectRoot);
        Files.writeString(projectRoot.resolve("src/[abc].txt"), "new\n");

        DiffSummary diffSummary = DiffSummary.created(
                23L,
                "task-23",
                tempRoot.resolve("snapshot").toString(),
                projectRoot.toString(),
                List.of(),
                List.of("src/[abc].txt"),
                List.of(),
                List.of()
        );
        GenerationArtifact artifact = GenerationArtifact.of(
                "diff_summary",
                "test",
                "diff",
                diffSummary.toPayload()
        );
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()), gitCommandExecutor(), outputRoot);

        GenerationCommitResult result = service.commit(23L, "task-23", artifact);

        assertEquals("committed", result.status());
        assertEquals(List.of("src/[abc].txt"), result.committedFiles());
        assertEquals(
                List.of("src/[abc].txt"),
                nonBlankLines(runGitForOutput(projectRoot, "show", "--pretty=", "--name-only", "HEAD"))
        );
    }

    @Test
    void shouldSkipWhenDiffSummaryIsMissing() {
        Path tempRoot = cleanTestRoot("skip");
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()), gitCommandExecutor(), tempRoot.resolve("code_output"));

        GenerationCommitResult result = service.commit(22L, "task-22", null);

        assertEquals("skipped", result.status());
        assertEquals("diff_summary_missing", result.reason());
    }

    @Test
    void shouldMapInterruptedRepositoryLookupToFailureWithoutFurtherGitCommands() throws Exception {
        Path tempRoot = cleanTestRoot("interrupted-root-lookup");
        Path outputRoot = tempRoot.resolve("code_output");
        Path projectRoot = Files.createDirectories(outputRoot.resolve("vue_project_24"));
        GitCommandExecutor gitExecutor = mock(GitCommandExecutor.class);
        when(gitExecutor.execute(any(Path.class), anyList(), anyMap(), anyString(), anyString()))
                .thenReturn(gitResult(ManagedProcessResult.Status.INTERRUPTED, null, "", "interrupted"));
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()), gitExecutor, outputRoot);

        GenerationCommitResult result = service.commit(
                24L,
                "task-24",
                diffArtifact(24L, projectRoot, "index.html")
        );

        assertEquals("failed", result.status());
        assertEquals("git_commit_interrupted", result.reason());
        verify(gitExecutor, times(1))
                .execute(any(Path.class), anyList(), anyMap(), anyString(), anyString());
    }

    @Test
    void shouldStopTemporaryIndexPreparationWhenHeadLookupIsInterrupted() throws Exception {
        Path tempRoot = cleanTestRoot("interrupted-index-prepare");
        Path outputRoot = tempRoot.resolve("code_output");
        Path projectRoot = Files.createDirectories(outputRoot.resolve("vue_project_25"));
        Path gitDirectory = Files.createDirectories(projectRoot.resolve(".git"));
        GitCommandExecutor gitExecutor = mock(GitCommandExecutor.class);
        when(gitExecutor.execute(any(Path.class), anyList(), anyMap(), anyString(), anyString()))
                .thenReturn(gitResult(
                        ManagedProcessResult.Status.COMPLETED,
                        0,
                        projectRoot.toString(),
                        ""
                ))
                .thenReturn(gitResult(
                        ManagedProcessResult.Status.COMPLETED,
                        0,
                        gitDirectory.toString(),
                        ""
                ))
                .thenReturn(gitResult(
                        ManagedProcessResult.Status.INTERRUPTED,
                        null,
                        "",
                        "interrupted"
                ));
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()), gitExecutor, outputRoot);

        GenerationCommitResult result = service.commit(
                25L,
                "task-25",
                diffArtifact(25L, projectRoot, "index.html")
        );

        assertEquals("failed", result.status());
        assertEquals("git_commit_interrupted", result.reason());
        verify(gitExecutor, times(3))
                .execute(any(Path.class), anyList(), anyMap(), anyString(), anyString());
    }

    @Test
    void shouldRejectDiffArtifactFromAnotherGenerationContext() throws Exception {
        Path tempRoot = cleanTestRoot("context-mismatch");
        Path outputRoot = tempRoot.resolve("code_output");
        Path projectRoot = Files.createDirectories(outputRoot.resolve("vue_project_31"));
        GitCommandExecutor gitExecutor = mock(GitCommandExecutor.class);
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()), gitExecutor, outputRoot);

        GenerationCommitResult result = service.commit(
                31L,
                "task-31",
                diffArtifact(32L, projectRoot, "index.html")
        );

        assertEquals("skipped", result.status());
        assertEquals("diff_summary_context_mismatch", result.reason());
        verifyNoInteractions(gitExecutor);
    }

    @Test
    void shouldRejectProjectDirectoryBelongingToAnotherApplication() throws Exception {
        Path tempRoot = cleanTestRoot("project-context-mismatch");
        Path outputRoot = tempRoot.resolve("code_output");
        Path anotherProject = Files.createDirectories(outputRoot.resolve("vue_project_33"));
        GitCommandExecutor gitExecutor = mock(GitCommandExecutor.class);
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()), gitExecutor, outputRoot);

        GenerationCommitResult result = service.commit(
                32L,
                "task-32",
                diffArtifact(32L, anotherProject, "index.html")
        );

        assertEquals("skipped", result.status());
        assertEquals("project_path_context_mismatch", result.reason());
        assertEquals("", result.projectPath());
        verifyNoInteractions(gitExecutor);
    }

    @Test
    void shouldRejectOutputRootReportedAsApplicationWorkspace() {
        Path tempRoot = cleanTestRoot("output-root-boundary");
        Path outputRoot = tempRoot.resolve("code_output");
        GitCommandExecutor gitExecutor = mock(GitCommandExecutor.class);
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                gitExecutor,
                outputRoot
        );

        GenerationCommitResult result = service.commit(
                38L,
                "task-38",
                diffArtifact(38L, outputRoot, "index.html")
        );

        assertEquals("skipped", result.status());
        assertEquals("project_path_context_mismatch", result.reason());
        verifyNoInteractions(gitExecutor);
    }

    @Test
    void shouldRejectMissingCanonicalApplicationWorkspace() {
        Path tempRoot = cleanTestRoot("missing-workspace");
        Path outputRoot = tempRoot.resolve("code_output");
        Path missingProjectRoot = outputRoot.resolve("vue_project_39");
        GitCommandExecutor gitExecutor = mock(GitCommandExecutor.class);
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                gitExecutor,
                outputRoot
        );

        GenerationCommitResult result = service.commit(
                39L,
                "task-39",
                diffArtifact(39L, missingProjectRoot, "index.html")
        );

        assertEquals("skipped", result.status());
        assertEquals("project_path_missing", result.reason());
        verifyNoInteractions(gitExecutor);
    }

    @Test
    void shouldRejectNonDirectoryAtCanonicalApplicationWorkspace() throws Exception {
        Path tempRoot = cleanTestRoot("workspace-regular-file");
        Path outputRoot = Files.createDirectories(tempRoot.resolve("code_output"));
        Path unsafeProjectPath = Files.writeString(outputRoot.resolve("vue_project_41"), "not a directory");
        GitCommandExecutor gitExecutor = mock(GitCommandExecutor.class);
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                gitExecutor,
                outputRoot
        );

        GenerationCommitResult result = service.commit(
                41L,
                "task-41",
                diffArtifact(41L, unsafeProjectPath, "index.html")
        );

        assertEquals("skipped", result.status());
        assertEquals("project_path_unsafe", result.reason());
        verifyNoInteractions(gitExecutor);
    }

    @Test
    void shouldRejectSymbolicLinkAtCanonicalApplicationWorkspace() throws Exception {
        Path tempRoot = cleanTestRoot("workspace-symbolic-link");
        Path outputRoot = Files.createDirectories(tempRoot.resolve("code_output"));
        Path externalProjectRoot = Files.createDirectory(tempRoot.resolve("external-project"));
        Path linkedProjectRoot = outputRoot.resolve("vue_project_40");
        createSymbolicLinkOrSkip(linkedProjectRoot, externalProjectRoot);
        GitCommandExecutor gitExecutor = mock(GitCommandExecutor.class);
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                gitExecutor,
                outputRoot
        );

        GenerationCommitResult result = service.commit(
                40L,
                "task-40",
                diffArtifact(40L, linkedProjectRoot, "index.html")
        );

        assertEquals("skipped", result.status());
        assertEquals("project_path_unsafe", result.reason());
        verifyNoInteractions(gitExecutor);
    }

    @Test
    void shouldRejectParentRepositoryInsteadOfCommittingOutsideProjectBoundary() throws Exception {
        Path tempRoot = cleanTestRoot("parent-repository");
        Path outputRoot = tempRoot.resolve("code_output");
        Path projectRoot = Files.createDirectories(outputRoot.resolve("vue_project_34"));
        Files.writeString(projectRoot.resolve("index.html"), "old\n");
        initGitRepository(outputRoot);
        String headBefore = runGitForOutput(outputRoot, "rev-parse", "HEAD").trim();
        Files.writeString(projectRoot.resolve("index.html"), "new\n");
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()), gitCommandExecutor(), outputRoot);

        GenerationCommitResult result = service.commit(
                34L,
                "task-34",
                diffArtifact(34L, projectRoot, "index.html")
        );

        assertEquals("skipped", result.status());
        assertEquals("git_repository_root_mismatch", result.reason());
        assertEquals(headBefore, runGitForOutput(outputRoot, "rev-parse", "HEAD").trim());
    }

    @Test
    void shouldExposeStableReasonInsteadOfGitErrorDetails() throws Exception {
        Path tempRoot = cleanTestRoot("git-error-sanitization");
        Path outputRoot = tempRoot.resolve("code_output");
        Path projectRoot = Files.createDirectories(outputRoot.resolve("vue_project_35"));
        GitCommandExecutor gitExecutor = mock(GitCommandExecutor.class);
        when(gitExecutor.execute(any(Path.class), anyList(), anyMap(), anyString(), anyString()))
                .thenReturn(new GitCommandResult(
                        ManagedProcessResult.Status.START_FAILED,
                        null,
                        "",
                        "provider-api-key=secret-value",
                        "failed"
                ));
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()), gitExecutor, outputRoot);

        GenerationCommitResult result = service.commit(
                35L,
                "task-35",
                diffArtifact(35L, projectRoot, "index.html")
        );

        assertEquals("failed", result.status());
        assertEquals("git_root_lookup_failed", result.reason());
        assertFalse(result.toPayload().toString().contains("secret-value"));
    }

    @Test
    void shouldRejectChangedFileCountBeyondConfiguredBoundaryBeforeGitExecution() throws Exception {
        Path tempRoot = cleanTestRoot("file-count-limit");
        Path outputRoot = tempRoot.resolve("code_output");
        Path projectRoot = outputRoot.resolve("vue_project_36");
        GenerationCommitProperties properties = new GenerationCommitProperties();
        properties.setMaxFilesPerCommit(1);
        GitCommandExecutor gitExecutor = mock(GitCommandExecutor.class);
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()), gitExecutor, outputRoot, properties);
        DiffSummary summary = DiffSummary.created(
                36L,
                "task-36",
                tempRoot.resolve("snapshot").toString(),
                projectRoot.toString(),
                List.of("one.txt", "two.txt"),
                List.of(),
                List.of(),
                List.of()
        );

        GenerationCommitResult result = service.commit(
                36L,
                "task-36",
                GenerationArtifact.of("diff_summary", "test", "diff", summary.toPayload())
        );

        assertEquals("skipped", result.status());
        assertEquals("changed_file_limit_exceeded", result.reason());
        verifyNoInteractions(gitExecutor);
    }

    @Test
    void shouldCommitLargePathSetWithoutUsingCommandLinePathspecArguments() throws Exception {
        Path tempRoot = cleanTestRoot("large-pathspec");
        Path outputRoot = tempRoot.resolve("code_output");
        Path projectRoot = outputRoot.resolve("vue_project_37");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("seed.txt"), "seed\n");
        initGitRepository(projectRoot);

        List<String> generatedFiles = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            String relativePath = "src/generated/group-%03d/component-%03d-%s.txt"
                    .formatted(index, index, "x".repeat(30));
            Path file = projectRoot.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "generated " + index + "\n");
            generatedFiles.add(relativePath);
        }
        assertTrue(generatedFiles.stream().mapToInt(String::length).sum() > 32_767);
        DiffSummary summary = DiffSummary.created(
                37L,
                "task-37",
                tempRoot.resolve("snapshot").toString(),
                projectRoot.toString(),
                generatedFiles,
                List.of(),
                List.of(),
                List.of()
        );
        GenerationCommitService service = SnapshotServiceTestFixture.commitService(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()), gitCommandExecutor(), outputRoot);

        GenerationCommitResult result = service.commit(
                37L,
                "task-37",
                GenerationArtifact.of("diff_summary", "test", "diff", summary.toPayload())
        );

        assertEquals("committed", result.status());
        assertEquals(generatedFiles.size(), result.committedFileCount());
        assertEquals(generatedFiles, result.committedFiles());
    }

    private void initGitRepository(Path projectRoot) throws Exception {
        runGit(projectRoot, "init");
        runGit(projectRoot, "config", "user.email", "test@example.com");
        runGit(projectRoot, "config", "user.name", "test");
        runGit(projectRoot, "add", ".");
        runGit(projectRoot, "commit", "-m", "initial");
    }

    private GitCommandExecutor gitCommandExecutor() {
        ManagedProcessExecutor processExecutor = new ManagedProcessExecutor(
                new ProjectProcessTerminator(new ExternalProcessProperties())
        );
        GenerationCommitProperties properties = new GenerationCommitProperties();
        properties.setCommandTimeout(Duration.ofSeconds(30));
        GenerationExecutionContextService executionContextService = mock(GenerationExecutionContextService.class);
        when(executionContextService.clampTimeout(anyString(), any(Duration.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return new GitCommandExecutor(processExecutor, properties, executionContextService);
    }

    private GenerationArtifact diffArtifact(Long appId, Path projectRoot, String changedFile) {
        DiffSummary summary = DiffSummary.created(
                appId,
                "task-" + appId,
                projectRoot.resolveSibling("snapshot").toString(),
                projectRoot.toString(),
                List.of(changedFile),
                List.of(),
                List.of(),
                List.of()
        );
        return GenerationArtifact.of("diff_summary", "test", "diff", summary.toPayload());
    }

    private GitCommandResult gitResult(
            ManagedProcessResult.Status status,
            Integer exitCode,
            String stdout,
            String errorDetail
    ) {
        return new GitCommandResult(status, exitCode, stdout, "", errorDetail);
    }

    private void runGit(Path workingDir, String... args) throws Exception {
        runGitForOutput(workingDir, args);
    }

    private String runGitForOutput(Path workingDir, String... args) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(workingDir.toFile());
        processBuilder.command(buildCommand(args));
        processBuilder.environment().put("GIT_AUTHOR_NAME", "test");
        processBuilder.environment().put("GIT_AUTHOR_EMAIL", "test@example.com");
        processBuilder.environment().put("GIT_COMMITTER_NAME", "test");
        processBuilder.environment().put("GIT_COMMITTER_EMAIL", "test@example.com");
        processBuilder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        processBuilder.environment().put(
                "GIT_CONFIG_GLOBAL",
                workingDir.resolve(".test-git-global-config").toAbsolutePath().normalize().toString()
        );
        processBuilder.environment().put(
                "XDG_CONFIG_HOME",
                workingDir.resolve(".test-git-xdg-config").toAbsolutePath().normalize().toString()
        );
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
        return output;
    }

    private List<String> buildCommand(String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        return command;
    }

    private Path cleanTestRoot(String caseName) {
        Path root = Path.of("target", "test-workspaces", "commit-service", caseName);
        FileUtil.del(root.toFile());
        return root;
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable in this environment: " + exception.getClass().getSimpleName());
        }
    }

    private void installFailingIfExecutedPreCommitHook(Path projectRoot) throws Exception {
        Path hook = projectRoot.resolve(".git/hooks/pre-commit");
        Files.writeString(hook, "#!/bin/sh\ntouch hook-ran.txt\nexit 1\n");
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            );
            Files.setPosixFilePermissions(hook, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Git for Windows 可直接执行带 shebang 的 hook，不支持 POSIX 权限时无需额外处理。
        }
    }

    private List<String> nonBlankLines(String value) {
        return value.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }
}
