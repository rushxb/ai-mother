package com.rush.rushaicodemother.service.devserver;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DevServerPreviewTargetResolverTest {

    private final DevServerPreviewTargetResolver resolver = new DevServerPreviewTargetResolver(
            new DevServerPreviewPathFactory("/api")
    );

    @Test
    void localTargetMustPreserveTheVitePublicBase() {
        DevServerPreviewRoute route = DevServerPreviewRoute.local(21L, "preview-node-a", 5180);

        assertEquals(
                "http://127.0.0.1:5180/api/app/dev-server/proxy/21/@vite/client?token=abc",
                resolver.httpTarget(route, "/@vite/client", "token=abc").toString()
        );
        assertEquals(
                "ws://127.0.0.1:5180/api/app/dev-server/proxy/21/?token=abc",
                resolver.webSocketTarget(route, "/", "token=abc").toString()
        );
    }

    @Test
    void remoteTargetMustUseTheSignedInternalHopWithoutLeakingTheOwnerPort() {
        DevServerPreviewRoute route = DevServerPreviewRoute.remote(
                21L,
                "preview-node-b",
                5180,
                URI.create("https://preview-node-b.internal/api")
        );

        assertEquals(
                "https://preview-node-b.internal/api/internal/dev-server/proxy/21/src/main.ts",
                resolver.httpTarget(route, "/src/main.ts", null).toString()
        );
        assertEquals(
                "wss://preview-node-b.internal/api/internal/dev-server/proxy/21/src/main.ts",
                resolver.webSocketTarget(route, "/src/main.ts", null).toString()
        );
    }
}
