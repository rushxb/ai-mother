package com.rush.rushaicodemother.orchestration.routing;

/**
 * 生成管道使用的标准路线名称。
 */
public final class GenerationRoute {

    public static final String CREATE = "create";
    public static final String READ_ONLY = "read_only";
    public static final String LIGHTWEIGHT_EDIT = "lightweight_edit";
    public static final String AGENT_EDIT = "agent_edit";
    public static final String SLOT_FILL = "slot_fill";
    public static final String HEAVY_GENERATION = "heavy_generation";

    private GenerationRoute() {
    }
}
