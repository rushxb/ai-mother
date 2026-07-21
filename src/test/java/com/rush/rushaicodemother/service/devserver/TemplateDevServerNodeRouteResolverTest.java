package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerInternalRoutingProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateDevServerNodeRouteResolverTest {

    @Test
    void shouldResolveOnlySafeNodeIdentifiers() {
        DevServerInternalRoutingProperties properties = new DevServerInternalRoutingProperties();
        properties.setBaseUrlTemplate("http://{nodeId}:8123/api/");
        TemplateDevServerNodeRouteResolver resolver = new TemplateDevServerNodeRouteResolver(properties);

        assertEquals(
                "http://preview-node-b:8123/api",
                resolver.resolve("preview-node-b").toString()
        );
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("node-b/../../admin"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("node b"));
    }
}
