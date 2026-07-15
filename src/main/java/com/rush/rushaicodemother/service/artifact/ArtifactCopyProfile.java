package com.rush.rushaicodemother.service.artifact;

import java.util.List;
import java.util.Locale;

/** Defines which generated artifacts are eligible for a lifecycle copy. */
enum ArtifactCopyProfile {

    GENERATED_SOURCE(
            List.of(".git", ".idea", "node_modules", "dist", "target"),
            List.of(
                    ".ai-code-install.stamp",
                    ".ai-code-critical.stamp",
                    ".ai-code-presentation.stamp"
            )
    ),
    DEPLOYMENT(List.of(), List.of());

    private final List<String> excludedDirectories;
    private final List<String> excludedFiles;

    ArtifactCopyProfile(List<String> excludedDirectories, List<String> excludedFiles) {
        this.excludedDirectories = List.copyOf(excludedDirectories);
        this.excludedFiles = List.copyOf(excludedFiles);
    }

    boolean excludesDirectory(String directoryName) {
        return containsIgnoreCase(excludedDirectories, directoryName);
    }

    boolean excludesFile(String fileName) {
        return containsIgnoreCase(excludedFiles, fileName);
    }

    List<String> excludedDirectories() {
        return excludedDirectories;
    }

    List<String> excludedFiles() {
        return excludedFiles;
    }

    private boolean containsIgnoreCase(List<String> candidates, String name) {
        if (name == null) {
            return false;
        }
        String normalizedName = name.toLowerCase(Locale.ROOT);
        return candidates.stream().anyMatch(candidate -> candidate.equals(normalizedName));
    }
}
