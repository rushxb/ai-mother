package com.rush.rushaicodemother.orchestration.runtime.tracing;

import java.util.Map;

/** 用于捕获并继续跨持久工作的生成跟踪的基础设施端口。 */
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
