package com.rush.rushaicodemother.service.devserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevServerPreviewPathFactoryTest {

    @Test
    void shouldBuildContextAwareApplicationScopedPaths() {
        DevServerPreviewPathFactory factory = new DevServerPreviewPathFactory("/api");

        assertEquals(
                "/api/app/dev-server/proxy/21/",
                factory.publicBasePath(21L)
        );
        assertEquals(
                "/api/app/dev-server/proxy/21/src/main.ts",
                factory.localUpstreamPath(21L, "/src/main.ts")
        );
        assertEquals(
                "/api/app/dev-server/proxy/21/",
                factory.localUpstreamPath(21L, "/")
        );
    }

    @Test
    void shouldRejectInvalidApplicationAndTargetPaths() {
        DevServerPreviewPathFactory factory = new DevServerPreviewPathFactory("");

        assertThrows(IllegalArgumentException.class, () -> factory.publicBasePath(null));
        assertThrows(IllegalArgumentException.class, () -> factory.localUpstreamPath(21L, "src/main.ts"));
        assertThrows(IllegalArgumentException.class, () -> factory.localUpstreamPath(21L, "/src\nmain.ts"));
    }
}
