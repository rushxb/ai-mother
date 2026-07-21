package com.rush.rushaicodemother.controller.app.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevServerWebSocketRequestParserTest {

    private final DevServerWebSocketRequestParser parser = new DevServerWebSocketRequestParser();

    @Test
    void shouldParseContextAwarePublicAndInternalPaths() {
        MockHttpServletRequest publicRequest = request(
                "/api/app/dev-server/proxy/21/@vite/client",
                "token=abc"
        );
        MockHttpServletRequest internalRequest = request(
                "/api/internal/dev-server/proxy/21/",
                null
        );

        DevServerWebSocketRequest publicTarget = parser.parsePublic(publicRequest);
        DevServerWebSocketRequest internalTarget = parser.parseInternal(internalRequest);

        assertEquals(21L, publicTarget.appId());
        assertEquals("/@vite/client", publicTarget.targetPath());
        assertEquals("token=abc", publicTarget.queryString());
        assertEquals("/", internalTarget.targetPath());
    }

    @Test
    void shouldRejectPrefixConfusionInvalidIdsAndControlCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parsePublic(request("/api/app/dev-server/proxy/0/", null)));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parsePublic(request("/api/internal/dev-server/proxy/21/", null)));
        MockHttpServletRequest controlQuery = request("/api/app/dev-server/proxy/21/", "token=a\nb");
        assertThrows(IllegalArgumentException.class, () -> parser.parsePublic(controlQuery));
    }

    private MockHttpServletRequest request(String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setContextPath("/api");
        request.setQueryString(query);
        return request;
    }
}
