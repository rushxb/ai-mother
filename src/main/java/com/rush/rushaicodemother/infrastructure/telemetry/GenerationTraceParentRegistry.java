package com.rush.rushaicodemother.infrastructure.telemetry;

import io.micrometer.tracing.TraceContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Bounded-by-active-tasks bridge for child observations emitted from model callback threads. */
@Component
public class GenerationTraceParentRegistry {

    private final ConcurrentMap<String, TraceContext> activeParents = new ConcurrentHashMap<>();

    Registration register(String taskId, TraceContext context) {
        if (taskId == null || taskId.isBlank() || context == null) {
            return Registration.NOOP;
        }
        TraceContext previous = activeParents.put(taskId, context);
        return () -> {
            if (previous == null) {
                activeParents.remove(taskId, context);
            } else {
                activeParents.replace(taskId, context, previous);
            }
        };
    }

    Optional<TraceContext> find(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeParents.get(taskId));
    }

    @FunctionalInterface
    interface Registration extends AutoCloseable {
        Registration NOOP = () -> { };

        @Override
        void close();
    }
}
