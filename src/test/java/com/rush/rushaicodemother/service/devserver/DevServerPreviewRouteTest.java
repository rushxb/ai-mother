package com.rush.rushaicodemother.service.devserver;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevServerPreviewRouteTest {

    @Test
    void shouldBuildHttpAndWebSocketTargetsWithoutFollowingUserControlledHosts() {
        DevServerPreviewRoute local = DevServerPreviewRoute.local(21L, "node-a", 5180);
        DevServerPreviewRoute remote = DevServerPreviewRoute.remote(
                21L,
                "node-b",
                5180,
                URI.create("http://node-b:8123/api")
        );

        assertEquals("http://127.0.0.1:5180/src/main.ts?raw=1",
                local.httpTarget("/src/main.ts", "raw=1").toString());
        assertEquals("ws://127.0.0.1:5180/src/main.ts?raw=1",
                local.webSocketTarget("/src/main.ts", "raw=1").toString());
        assertEquals("http://node-b:8123/api/internal/dev-server/proxy/21/src/main.ts",
                remote.httpTarget("/src/main.ts", null).toString());
    }

    @Test
    void shouldRejectInvalidRoutesAndControlCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> DevServerPreviewRoute.remote(21L, "node-b", 5180, null));
        DevServerPreviewRoute route = DevServerPreviewRoute.local(21L, "node-a", 5180);
        assertThrows(IllegalArgumentException.class,
                () -> route.httpTarget("/src\nmain.ts", null));
    }
}
