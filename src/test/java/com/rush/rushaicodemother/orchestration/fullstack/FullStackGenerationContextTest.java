package com.rush.rushaicodemother.orchestration.fullstack;

import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullStackGenerationContextTest {

    @Test
    void shouldRoundTripCanonicalFullStackConnectionFacts() {
        FullStackGenerationContext original = context();

        GenerationArtifact persisted = original.toArtifact();
        FullStackGenerationContext restored =
                FullStackGenerationContext.fromArtifact(persisted, 7L);

        assertEquals(FullStackGenerationContext.KEY, persisted.key());
        assertEquals(original, restored);
    }

    @Test
    void restoredContextMustRejectFrontendEnvironmentThatDriftsFromBackendAddress() {
        Map<String, Object> payload = new LinkedHashMap<>(context().toPayload());
        payload.put("frontendApiEnvValue", "http://127.0.0.1:19999/api");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> FullStackGenerationContext.fromPayload(payload, 7L)
        );

        assertTrue(exception.getMessage().contains("frontendApiEnvValue"));
    }

    private FullStackGenerationContext context() {
        return new FullStackGenerationContext(
                7L,
                "target/workspaces/7",
                "target/workspaces/7/frontend",
                "target/workspaces/7/backend",
                17007,
                18007,
                "http://127.0.0.1:17007",
                "http://127.0.0.1:18007",
                "/api",
                "VITE_API_BASE_URL",
                "http://127.0.0.1:18007/api",
                ":18007",
                "reserved"
        );
    }
}
