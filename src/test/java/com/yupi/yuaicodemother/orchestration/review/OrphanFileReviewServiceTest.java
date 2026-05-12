package com.yupi.yuaicodemother.orchestration.review;

import com.yupi.yuaicodemother.orchestration.artifact.ChangePlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OrphanFileReviewServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void reviewShouldReportUnreferencedLandingFile() throws Exception {
        Files.createDirectories(tempDir.resolve("src/views"));
        Files.createDirectories(tempDir.resolve("src/router"));
        Files.writeString(tempDir.resolve("src/views/LandingView.vue"), "<template>landing</template>");
        Files.writeString(tempDir.resolve("src/router/index.ts"), "import BlogView from '../views/BlogView.vue'");

        OrphanFileReviewService.OrphanFileReviewResult result = new OrphanFileReviewService().review(tempDir, null);

        assertTrue(result.orphanCandidates().contains("src/views/LandingView.vue"));
    }

    @Test
    void reviewShouldExposeDeleteAllowedFromChangePlan() throws Exception {
        Files.createDirectories(tempDir.resolve("src/views"));
        Files.writeString(tempDir.resolve("src/views/LandingView.vue"), "<template>landing</template>");
        ChangePlan changePlan = new ChangePlan("v1", "feature_update", List.of(), List.of(), List.of("src/views/LandingView.vue"), List.of(), "review_only", "manual_retry_without_snapshot");

        OrphanFileReviewService.OrphanFileReviewResult result = new OrphanFileReviewService().review(tempDir, changePlan);

        assertTrue(result.deleteAllowedFiles().contains("src/views/LandingView.vue"));
    }
}
