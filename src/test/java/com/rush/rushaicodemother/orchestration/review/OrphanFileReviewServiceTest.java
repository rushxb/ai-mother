package com.rush.rushaicodemother.orchestration.review;

import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OrphanFileReviewServiceTest {

    @Test
    void reviewShouldReportUnreferencedLandingFile() throws Exception {
        Path tempDir = testDir("unreferenced-landing");
        Files.createDirectories(tempDir.resolve("src/views"));
        Files.createDirectories(tempDir.resolve("src/router"));
        Files.writeString(tempDir.resolve("src/views/LandingView.vue"), "<template>landing</template>");
        Files.writeString(tempDir.resolve("src/router/index.ts"), "import BlogView from '../views/BlogView.vue'");

        OrphanFileReviewService.OrphanFileReviewResult result = new OrphanFileReviewService().review(tempDir, null);

        assertTrue(result.orphanCandidates().contains("src/views/LandingView.vue"));
    }

    @Test
    void reviewShouldExposeDeleteAllowedFromChangePlan() throws Exception {
        Path tempDir = testDir("delete-allowed");
        Files.createDirectories(tempDir.resolve("src/views"));
        Files.writeString(tempDir.resolve("src/views/LandingView.vue"), "<template>landing</template>");
        ChangePlan changePlan = new ChangePlan("v1", "feature_update", List.of(), List.of(), List.of("src/views/LandingView.vue"), List.of(), "review_only", "manual_retry_without_snapshot");

        OrphanFileReviewService.OrphanFileReviewResult result = new OrphanFileReviewService().review(tempDir, changePlan);

        assertTrue(result.deleteAllowedFiles().contains("src/views/LandingView.vue"));
    }

    private Path testDir(String name) throws Exception {
        Path root = Path.of("target/test-workspaces/orphan-file-review", name)
                .toAbsolutePath()
                .normalize();
        deleteIfExists(root);
        Files.createDirectories(root);
        return root;
    }

    private void deleteIfExists(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }
}
