package com.rush.rushaicodemother.controller.support;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationSseEventMapperTest {

    private final GenerationSseEventMapper mapper = new GenerationSseEventMapper();

    @Test
    void domainEventsMustRetainTypeAndJsonPayloadBeforeDoneSignal() {
        List<ServerSentEvent<String>> mapped = mapper.map(
                        Flux.just(GenerationStreamEvent.aiDelta("hello")))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertNotNull(mapped);
        assertEquals(2, mapped.size());
        assertEquals(GenerationStreamEvent.AI_DELTA, mapped.getFirst().event());
        assertTrue(mapped.getFirst().data().contains("hello"));
        assertEquals("done", mapped.getLast().event());
        assertEquals("", mapped.getLast().data());
    }

    @Test
    void nullDomainStreamMustBeRejectedAtBoundary() {
        assertThrows(IllegalArgumentException.class, () -> mapper.map(null));
    }
}
