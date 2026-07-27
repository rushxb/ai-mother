package com.rush.rushaicodemother.monitor.span;

/** 完整生成跨度的观察者端口。实现必须能够安全地重复调用。 */
@FunctionalInterface
public interface GenerationSpanSink {

    void record(GenerationSpanObservation observation);
}
