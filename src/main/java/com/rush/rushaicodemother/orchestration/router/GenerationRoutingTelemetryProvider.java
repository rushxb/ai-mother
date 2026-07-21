package com.rush.rushaicodemother.orchestration.router;

/** Supplies cached production telemetry without exposing persistence details to routing policies. */
public interface GenerationRoutingTelemetryProvider {

    GenerationRoutingTelemetrySnapshot snapshot(Long appId, Long userId);
}
