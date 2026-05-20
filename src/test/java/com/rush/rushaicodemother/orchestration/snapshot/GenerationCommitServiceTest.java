package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationCommitResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationCommitServiceTest {

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

        GenerationCommitService service = new GenerationCommitService(
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                outputRoot
        );
        GenerationCommitResult result = service.commit(21L, "task-21", artifact);

        assertEquals("committed", result.status());
        assertTrue(result.commitId().length() >= 7);
        assertEquals(List.of("src/App.vue"), result.committedFiles());
        assertEquals("local_git", result.provider());
    }

    @Test
    void shouldSkipWhenDiffSummaryIsMissing() {
        Path tempRoot = cleanTestRoot("skip");
        GenerationCommitService service = new GenerationCommitService(
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                tempRoot.resolve("code_output")
        );

        GenerationCommitResult result = service.commit(22L, "task-22", null);

        assertEquals("skipped", result.status());
        assertEquals("diff_summary_missing", result.reason());
    }

    private void initGitRepository(Path projectRoot) throws Exception {
        runGit(projectRoot, "init");
        runGit(projectRoot, "config", "user.email", "test@example.com");
        runGit(projectRoot, "config", "user.name", "test");
        runGit(projectRoot, "add", ".");
        runGit(projectRoot, "commit", "-m", "initial");
    }

    private void runGit(Path workingDir, String... args) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(workingDir.toFile());
        processBuilder.command(buildCommand(args));
        processBuilder.environment().put("GIT_AUTHOR_NAME", "test");
        processBuilder.environment().put("GIT_AUTHOR_EMAIL", "test@example.com");
        processBuilder.environment().put("GIT_COMMITTER_NAME", "test");
        processBuilder.environment().put("GIT_COMMITTER_EMAIL", "test@example.com");
        Process process = processBuilder.start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException(new String(process.getErrorStream().readAllBytes()));
        }
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
}
