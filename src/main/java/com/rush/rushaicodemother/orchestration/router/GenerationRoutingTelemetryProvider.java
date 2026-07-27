package com.rush.rushaicodemother.orchestration.router;

/** 提供缓存的生产遥测数据，而无需向路由策略公开持久性详细信息。 */
public interface GenerationRoutingTelemetryProvider {

    GenerationRoutingTelemetrySnapshot snapshot(Long appId, Long userId);
}
