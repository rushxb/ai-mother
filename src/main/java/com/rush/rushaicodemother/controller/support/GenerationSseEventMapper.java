package com.rush.rushaicodemother.controller.support;

import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Maps domain generation events to the shared SSE wire format. */
@Component
public class GenerationSseEventMapper {

    public Flux<ServerSentEvent<String>> map(Flux<GenerationStreamEvent> events) {
        if (events == null) {
            throw new IllegalArgumentException("generation event stream cannot be null");
        }
        return events
                .map(event -> ServerSentEvent.<String>builder()
                        .event(event.getType())
                        .data(JSONUtil.toJsonStr(event))
                        .build())
                .concatWith(Mono.just(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("")
                        .build()));
    }
}
