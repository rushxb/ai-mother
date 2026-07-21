package com.rush.rushaicodemother.orchestration.runtime.tracing;

import java.util.Map;

/** Infrastructure port for capturing and continuing a generation trace across durable work. */
public interface GenerationTraceContextBridge {

    GenerationTraceContextBridge NOOP = new GenerationTraceContextBridge() {
        @Override
        public GenerationTraceContext capture() {
            return GenerationTraceContext.empty();
        }

        @Override
        public Runnable wrap(GenerationTraceContext context,
                             String spanName,
                             Map<String, String> tags,
                             Runnable task) {
            return task;
        }
    };

    GenerationTraceContext capture();

    Runnable wrap(GenerationTraceContext context,
                  String spanName,
                  Map<String, String> tags,
                  Runnable task);
}
