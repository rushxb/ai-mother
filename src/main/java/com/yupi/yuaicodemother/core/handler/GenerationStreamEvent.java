package com.yupi.yuaicodemother.core.handler;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 应用生成过程中的结构化流事件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationStreamEvent {

    public static final String AI_DELTA = "ai_delta";
    public static final String AI_THINKING_DELTA = "ai_thinking_delta";
    public static final String TOOL_CALL = "tool_call";
    public static final String TOOL_RESULT = "tool_result";
    public static final String BUILD_RESULT = "build_result";
    public static final String AGENT_EVENT = "agent_event";
    public static final String GENERATION_ERROR = "generation_error";
    public static final String REPAIR_START = "repair_start";
    public static final String GENERATION_STOPPED = "generation_stopped";

    private String type;

    /**
     * 面向前端直接追加展示的文本
     */
    private String text;

    /**
     * 结构化数据，供前端渲染状态卡、工具调用和错误详情
     */
    private Map<String, Object> data;

    public static GenerationStreamEvent aiDelta(String text) {
        return GenerationStreamEvent.builder()
                .type(AI_DELTA)
                .text(StrUtil.blankToDefault(text, ""))
                .build();
    }

    public static GenerationStreamEvent aiThinkingDelta(String text) {
        return GenerationStreamEvent.builder()
                .type(AI_THINKING_DELTA)
                .text(StrUtil.blankToDefault(text, ""))
                .build();
    }

    public static GenerationStreamEvent toolCall(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(TOOL_CALL)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    public static GenerationStreamEvent toolResult(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(TOOL_RESULT)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    public static GenerationStreamEvent buildResult(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(BUILD_RESULT)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    public static GenerationStreamEvent agentEvent(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(AGENT_EVENT)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    public static GenerationStreamEvent generationError(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(GENERATION_ERROR)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    public static GenerationStreamEvent repairStart(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(REPAIR_START)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    public static GenerationStreamEvent generationStopped(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(GENERATION_STOPPED)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }
}
