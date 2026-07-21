package com.rush.rushaicodemother.core.handler;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationPublicEventSanitizerTest {

    @Test
    void toolProjectionMustDropRuntimePayloadAndExposeOnlyRedactedBoundedPreview() {
        String secret = "prod-secret-do-not-publish";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", "call-1");
        data.put("toolName", "writeFile");
        data.put("filePath", "src/App.vue");
        data.put("arguments", "{\"content\":\"password=" + secret + "\"}");
        data.put("result", "raw file result " + secret);
        data.put("content", "const password = \"" + secret + "\";");
        data.put("providerToken", secret);

        GenerationStreamEvent sanitized = GenerationPublicEventSanitizer.sanitize(
                GenerationStreamEvent.toolResult("raw output " + secret, data));

        assertEquals("[工具完成] writeFile src/App.vue", sanitized.getText());
        assertEquals("call-1", sanitized.getData().get("requestId"));
        assertFalse(sanitized.getData().containsKey("arguments"));
        assertFalse(sanitized.getData().containsKey("result"));
        assertFalse(sanitized.getData().containsKey("providerToken"));
        assertFalse(String.valueOf(sanitized.getData()).contains(secret));
        assertTrue(String.valueOf(sanitized.getData().get("content")).contains("[REDACTED]"));
    }

    @Test
    void privateThinkingMustNeverProduceAPublicEvent() {
        assertNull(GenerationPublicEventSanitizer.sanitize(
                GenerationStreamEvent.aiThinkingDelta("private reasoning")));
    }

    @Test
    void ordinaryEventMustRedactNestedSecretsAndBoundOversizedValues() {
        String secret = "nested-secret";
        GenerationStreamEvent sanitized = GenerationPublicEventSanitizer.sanitize(
                GenerationStreamEvent.generationStage(
                        "Authorization: Bearer " + secret,
                        Map.of(
                                "detail", Map.of("password", secret, "summary", "safe"),
                                "large", "x".repeat(10_000)
                        )
                ));

        assertFalse(sanitized.getText().contains(secret));
        assertFalse(String.valueOf(sanitized.getData()).contains(secret));
        assertEquals(Map.of("summary", "safe"), sanitized.getData().get("detail"));
        assertTrue(String.valueOf(sanitized.getData().get("large")).length() <= 2_000);
    }
}
