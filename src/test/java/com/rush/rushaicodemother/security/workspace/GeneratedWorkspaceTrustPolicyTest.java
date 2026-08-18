package com.rush.rushaicodemother.security.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneratedWorkspaceTrustPolicyTest {

    private final GeneratedWorkspaceTrustPolicy policy = new GeneratedWorkspaceTrustPolicy();

    @TempDir
    Path projectRoot;

    @ParameterizedTest
    @ValueSource(strings = {
            ".npmrc",
            ".pnpmfile.mjs",
            ".pnpmfile.cjs",
            "pnpm-workspace.yaml",
            "pnpm-workspace.yml"
    })
    void executableWorkspaceMustRejectProjectLevelPnpmControlFiles(String controlFileName) throws Exception {
        writeSafeManifest();
        Files.writeString(projectRoot.resolve(controlFileName), "untrusted", StandardCharsets.UTF_8);

        String rejectionReason = policy.validateExecutableWorkspace(projectRoot);

        assertEquals(
                "generated_workspace_forbidden_control_file:" + controlFileName,
                rejectionReason
        );
    }

    @Test
    void executableWorkspaceMustValidateCurrentManifestContent() throws Exception {
        Files.writeString(
                projectRoot.resolve("package.json"),
                "{\"dependencies\":{\"unsafe\":\"https://attacker.invalid/package.tgz\"}}",
                StandardCharsets.UTF_8
        );

        String rejectionReason = policy.validateExecutableWorkspace(projectRoot);

        assertEquals(
                "executable_manifest_forbidden_dependency_source:unsafe",
                rejectionReason
        );
    }

    @Test
    void executableWorkspaceMayReuseTrustedTemplateLockfile() throws Exception {
        writeSafeManifest();
        Files.writeString(
                projectRoot.resolve("pnpm-lock.yaml"),
                "lockfileVersion: '9.0'",
                StandardCharsets.UTF_8
        );

        assertEquals("", policy.validateExecutableWorkspace(projectRoot));
    }

    @Test
    void executableWorkspaceMustRejectOversizedManifestWithoutParsingIt() throws Exception {
        Files.writeString(
                projectRoot.resolve("package.json"),
                " ".repeat(256 * 1024 + 1),
                StandardCharsets.UTF_8
        );

        assertEquals(
                "executable_manifest_too_large",
                policy.validateExecutableWorkspace(projectRoot)
        );
    }

    private void writeSafeManifest() throws Exception {
        Files.writeString(
                projectRoot.resolve("package.json"),
                "{\"packageManager\":\"pnpm@9.15.0\",\"dependencies\":{\"vue\":\"3.5.0\"}}",
                StandardCharsets.UTF_8
        );
    }
}
