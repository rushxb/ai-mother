package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.infrastructure.process.ProjectCommandResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoBuildDiagnosticBoundaryTest {

    @Test
    void publicDiagnosticsMustHideWorkspacePathsAndSecrets() {
        ProjectCommandResult commandResult = new ProjectCommandResult(
                ProjectCommandResult.Status.FAILED,
                "go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...",
                1,
                "C:\\Users\\rush\\workspace\\internal\\service.go:12:3: compile failed\n"
                        + "provider-api-key=secret-value",
                "退出码 1"
        );
        GoBuildResult result = new GoBuildResult(
                false,
                "test",
                "C:\\Users\\rush\\workspace",
                "Go 项目编译未通过，registry-token=summary-secret",
                commandResult
        );

        String report = result.toPublicDiagnosticReport();
        String summary = result.toPublicFailureSummary();

        assertFalse(report.contains("secret-value"));
        assertFalse(report.contains("C:\\Users\\rush"));
        assertFalse(summary.contains("summary-secret"));
        assertTrue(report.contains("service.go:12:3"));
        assertTrue(summary.contains("compile failed"));
    }
}
