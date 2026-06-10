package com.rush.rushaicodemother.orchestration.edit;

/**
 * 轻量编辑路由判断结果。
 */
public record GenerationEditRouteResult(
        String route,
        String reason,
        double confidence,
        boolean requiresBuild
) {

    public static final String ROUTE_LIGHTWEIGHT_EDIT = "lightweight_edit";
    public static final String ROUTE_HEAVY_GENERATION = "heavy_generation";

    public boolean isLightweightEdit() {
        return ROUTE_LIGHTWEIGHT_EDIT.equals(route);
    }

    public static GenerationEditRouteResult lightweightEdit(String reason, double confidence, boolean requiresBuild) {
        return new GenerationEditRouteResult(ROUTE_LIGHTWEIGHT_EDIT, reason, confidence, requiresBuild);
    }

    public static GenerationEditRouteResult heavyGeneration(String reason) {
        return new GenerationEditRouteResult(ROUTE_HEAVY_GENERATION, reason, 1.0, true);
    }
}
