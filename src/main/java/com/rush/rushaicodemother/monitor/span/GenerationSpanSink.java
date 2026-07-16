package com.rush.rushaicodemother.monitor.span;

/** Observer port for completed generation spans. Implementations must be safe to call repeatedly. */
@FunctionalInterface
public interface GenerationSpanSink {

    void record(GenerationSpanObservation observation);
}
