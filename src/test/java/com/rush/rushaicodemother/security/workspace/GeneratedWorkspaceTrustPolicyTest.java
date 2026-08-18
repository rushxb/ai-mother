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
    void executableWorkspaceMustRejectPackageManagerResolutionControls() throws Exception {
        Files.writeString(
                projectRoot.resolve("package.json"),
                """
                        {
                          "packageManager": "pnpm@9.15.0",
                          "pnpm": {
                            "overrides": {
                              "vue": "https://attacker.invalid/vue.tgz"
                            }
                          }
                        }
                        """,
                StandardCharsets.UTF_8
        );

        assertEquals(
                "executable_manifest_package_manager_controls_forbidden:pnpm",
                policy.validateExecutableWorkspace(projectRoot)
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
    void executableWorkspaceMustRejectExternalTarballFromCurrentLockfile() throws Exception {
        writeSafeManifest();
        Files.writeString(
                projectRoot.resolve("pnpm-lock.yaml"),
                """
                        lockfileVersion: '9.0'
                        importers:
                          .: {}
                        packages:
                          vue@3.5.0:
                            resolution:
                              tarball: https://attacker.invalid/vue.tgz
                        """,
                StandardCharsets.UTF_8
        );

        assertEquals(
                "generated_workspace_lockfile_external_resolution",
                policy.validateExecutableWorkspace(projectRoot)
        );
    }

    @Test
    void executableWorkspaceMustRejectRegistryPackageWithoutIntegrity() throws Exception {
        writeSafeManifest();
        Files.writeString(
                projectRoot.resolve("pnpm-lock.yaml"),
                """
                        lockfileVersion: '9.0'
                        importers:
                          .: {}
                        packages:
                          vue@3.5.0:
                            resolution: {}
                        """,
                StandardCharsets.UTF_8
        );

        assertEquals(
                "generated_workspace_lockfile_integrity_missing",
                policy.validateExecutableWorkspace(projectRoot)
        );
    }

    @Test
    void executableWorkspaceMustRejectMalformedSha512Integrity() throws Exception {
        writeSafeManifest();
        Files.writeString(
                projectRoot.resolve("pnpm-lock.yaml"),
                """
                        lockfileVersion: '9.0'
                        packages:
                          vue@3.5.0:
                            resolution:
                              integrity: sha512-YWJjZA==
                        """,
                StandardCharsets.UTF_8
        );

        assertEquals(
                "generated_workspace_lockfile_integrity_invalid",
                policy.validateExecutableWorkspace(projectRoot)
        );
    }

    @Test
    void executableWorkspaceMustRejectNonRegistryResolutionMetadata() throws Exception {
        writeSafeManifest();
        Files.writeString(
                projectRoot.resolve("pnpm-lock.yaml"),
                """
                        lockfileVersion: '9.0'
                        packages:
                          vue@3.5.0:
                            resolution:
                              integrity: sha512-UrcABB+4bUrFABwbluTIBErXwvbsU/V7TZWfmbgJfbkwiBuziS9gxdODUyuiecfdGQ85jglMW6juS3+z5TsKLw==
                              directory: ../untrusted-package
                        """,
                StandardCharsets.UTF_8
        );

        assertEquals(
                "generated_workspace_lockfile_external_resolution",
                policy.validateExecutableWorkspace(projectRoot)
        );
    }

    @Test
    void executableWorkspaceMustRejectUnsupportedLockfileVersion() throws Exception {
        writeSafeManifest();
        Files.writeString(
                projectRoot.resolve("pnpm-lock.yaml"),
                "lockfileVersion: '8.0'",
                StandardCharsets.UTF_8
        );

        assertEquals(
                "generated_workspace_lockfile_version_unsupported",
                policy.validateExecutableWorkspace(projectRoot)
        );
    }

    @Test
    void executableWorkspaceMustRejectExternalPackageIdentityFromCurrentLockfile() throws Exception {
        writeSafeManifest();
        Files.writeString(
                projectRoot.resolve("pnpm-lock.yaml"),
                """
                        lockfileVersion: '9.0'
                        importers:
                          .: {}
                        packages:
                          'https://attacker.invalid/vue.tgz':
                            resolution:
                              integrity: sha512-YWJjZA==
                        """,
                StandardCharsets.UTF_8
        );

        assertEquals(
                "generated_workspace_lockfile_external_resolution",
                policy.validateExecutableWorkspace(projectRoot)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "vue-web-basic",
            "vue-web-mobile",
            "vue-web-landing",
            "vue-web-admin"
    })
    void executableWorkspaceMustAcceptRepositoryTemplateLockfiles(String templateId) {
        Path templateRoot = Path.of(
                "src",
                "main",
                "resources",
                "project-templates",
                templateId
        ).toAbsolutePath().normalize();

        assertEquals("", policy.validateExecutableWorkspace(templateRoot));
    }

    @Test
    void executableWorkspaceMustRejectLockfileResolutionControls() throws Exception {
        writeSafeManifest();
        Files.writeString(
                projectRoot.resolve("pnpm-lock.yaml"),
                """
                        lockfileVersion: '9.0'
                        patchedDependencies:
                          vue@3.5.0: patches/vue.patch
                        """,
                StandardCharsets.UTF_8
        );

        assertEquals(
                "generated_workspace_lockfile_resolution_controls_forbidden",
                policy.validateExecutableWorkspace(projectRoot)
        );
    }

    @Test
    void executableWorkspaceMustFailClosedOnMalformedPackageCatalog() throws Exception {
        writeSafeManifest();
        Files.writeString(
                projectRoot.resolve("pnpm-lock.yaml"),
                """
                        lockfileVersion: '9.0'
                        packages: not-a-map
                        """,
                StandardCharsets.UTF_8
        );

        assertEquals(
                "generated_workspace_lockfile_invalid",
                policy.validateExecutableWorkspace(projectRoot)
        );
    }

    @Test
    void executableWorkspaceMustRejectNestedWorkspaceImporter() throws Exception {
        writeSafeManifest();
        Files.writeString(
                projectRoot.resolve("pnpm-lock.yaml"),
                """
                        lockfileVersion: '9.0'
                        importers:
                          .: {}
                          packages/hidden: {}
                        """,
                StandardCharsets.UTF_8
        );

        assertEquals(
                "generated_workspace_lockfile_workspace_scope_forbidden",
                policy.validateExecutableWorkspace(projectRoot)
        );
    }

    @Test
    void executableWorkspaceMustRejectExternalImporterResolution() throws Exception {
        writeSafeManifest();
        Files.writeString(
                projectRoot.resolve("pnpm-lock.yaml"),
                """
                        lockfileVersion: '9.0'
                        importers:
                          .:
                            dependencies:
                              vue:
                                specifier: 3.5.0
                                version: https://attacker.invalid/vue.tgz
                        """,
                StandardCharsets.UTF_8
        );

        assertEquals(
                "generated_workspace_lockfile_external_resolution",
                policy.validateExecutableWorkspace(projectRoot)
        );
    }

    @Test
    void executableWorkspaceMustRejectExternalSnapshotResolution() throws Exception {
        writeSafeManifest();
        Files.writeString(
                projectRoot.resolve("pnpm-lock.yaml"),
                """
                        lockfileVersion: '9.0'
                        snapshots:
                          vue@3.5.0:
                            dependencies:
                              malicious-package: https://attacker.invalid/package.tgz
                        """,
                StandardCharsets.UTF_8
        );

        assertEquals(
                "generated_workspace_lockfile_external_resolution",
                policy.validateExecutableWorkspace(projectRoot)
        );
    }

    @Test
    void executableWorkspaceMustFailClosedOnDuplicateLockfileKeys() throws Exception {
        writeSafeManifest();
        Files.writeString(
                projectRoot.resolve("pnpm-lock.yaml"),
                """
                        lockfileVersion: '9.0'
                        lockfileVersion: '9.0'
                        """,
                StandardCharsets.UTF_8
        );

        assertEquals(
                "generated_workspace_lockfile_invalid",
                policy.validateExecutableWorkspace(projectRoot)
        );
    }

    @Test
    void executableWorkspaceMustRejectOversizedLockfileWithoutParsingIt() throws Exception {
        writeSafeManifest();
        Files.writeString(
                projectRoot.resolve("pnpm-lock.yaml"),
                "lockfileVersion: '9.0'\n#" + "x".repeat(4 * 1024 * 1024),
                StandardCharsets.UTF_8
        );

        assertEquals(
                "generated_workspace_lockfile_too_large",
                policy.validateExecutableWorkspace(projectRoot)
        );
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
