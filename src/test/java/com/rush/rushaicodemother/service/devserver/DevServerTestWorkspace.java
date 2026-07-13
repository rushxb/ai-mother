package com.rush.rushaicodemother.service.devserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

final class DevServerTestWorkspace {

    private static final Path ROOT = Path.of("target", "dev-server-test-work")
            .toAbsolutePath()
            .normalize();

    private DevServerTestWorkspace() {
    }

    static Path create(String prefix) throws IOException {
        return Files.createDirectories(ROOT.resolve(prefix + "-" + UUID.randomUUID()));
    }

    static void delete(Path workspace) throws IOException {
        if (workspace == null) {
            return;
        }
        Path normalized = workspace.toAbsolutePath().normalize();
        if (!normalized.startsWith(ROOT)) {
            throw new IllegalArgumentException("Refusing to delete outside test workspace root");
        }
        if (!Files.exists(normalized)) {
            return;
        }
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
