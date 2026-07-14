package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.ai.model.message.BuildResultMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildResultDiagnosticBoundaryTest {

    @Test
    void publicDiagnosticsShouldBeSanitizedWhileInternalDiagnosticsRemainComplete() {
        VueBuildCommandResult commandResult = new VueBuildCommandResult(
                "pnpm run build",
                false,
                1,
                false,
                "D:\\Users\\rush\\workspace\\src\\App.vue:8:3 Cannot find module 'missing'\n"
                        + "provider-api-key=secret-value",
                "Authorization: Bearer command-secret"
        );
        VueBuildResult buildResult = new VueBuildResult(
                false,
                "build",
                "D:\\Users\\rush\\workspace",
                "pnpm run build failed, registry-token=summary-secret",
                null,
                commandResult
        );

        String internalReport = buildResult.toInternalDiagnosticReport();
        String publicReport = buildResult.toPublicDiagnosticReport();
        String publicFailureSummary = buildResult.toPublicFailureSummary();

        assertTrue(internalReport.contains("secret-value"));
        assertTrue(internalReport.contains("command-secret"));
        assertFalse(publicReport.contains("secret-value"));
        assertFalse(publicReport.contains("command-secret"));
        assertFalse(publicReport.contains("summary-secret"));
        assertFalse(publicReport.contains("D:\\Users\\rush"));
        assertTrue(publicReport.contains("App.vue:8:3"));
        assertTrue(publicReport.contains("Cannot find module 'missing'"));
        assertFalse(publicFailureSummary.contains("secret-value"));
        assertFalse(publicFailureSummary.contains("summary-secret"));
        assertTrue(publicFailureSummary.contains("Cannot find module 'missing'"));
        assertFalse(buildResult.publicSummary().contains("summary-secret"));
        assertFalse(buildResult.publicProjectPath().contains("D:\\Users\\rush"));

        BuildResultMessage streamMessage = new BuildResultMessage(buildResult);
        assertFalse(streamMessage.getProjectPath().contains("D:\\Users\\rush"));
        assertFalse(streamMessage.getSummary().contains("summary-secret"));
        assertFalse(streamMessage.getReport().contains("secret-value"));
        assertTrue(streamMessage.getReport().contains("Cannot find module 'missing'"));
    }
}
