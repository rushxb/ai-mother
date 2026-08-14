package com.rush.rushaicodemother.core.handler;

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
    public static final String GENERATION_STAGE = "generation_stage";
    public static final String AGENT_EVENT = "agent_event";
    public static final String GENERATION_ERROR = "generation_error";
    public static final String REPAIR_START = "repair_start";
    public static final String GENERATION_STOPPED = "generation_stopped";
    public static final String DEV_SERVER_VALIDATION = "dev_server_validation";
    public static final String FIRST_PREVIEW_READY = "first_preview_ready";
    public static final String TASK_TERMINAL = "task_terminal";

    private String type;

    /**
     * 面向前端直接追加展示的文本
     */
    private String text;

    /**
     * 结构化数据，供前端渲染状态卡、工具调用和错误详情
     */
    private Map<String, Object> data;

    /**
 * 返回 AI 增量。
 *
 * @param text {@code text} 对应的调用参数
 * @return 生成流事件
 */
    public static GenerationStreamEvent aiDelta(String text) {
        return GenerationStreamEvent.builder()
                .type(AI_DELTA)
                .text(StrUtil.blankToDefault(text, ""))
                .build();
    }

    /**
 * 返回 AI{@code Thinking}增量。
 *
 * @param text {@code text} 对应的调用参数
 * @return 生成流事件
 */
    public static GenerationStreamEvent aiThinkingDelta(String text) {
        return GenerationStreamEvent.builder()
                .type(AI_THINKING_DELTA)
                .text(StrUtil.blankToDefault(text, ""))
                .build();
    }

    /**
 * 将当前对象转换为{@code ol}调用。
 *
 * @param text {@code text} 对应的调用参数
 * @param data {@code data} 对应的调用参数
 * @return {@code ol}调用
 */
    public static GenerationStreamEvent toolCall(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(TOOL_CALL)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    /**
 * 将当前对象转换为{@code ol}结果。
 *
 * @param text {@code text} 对应的调用参数
 * @param data {@code data} 对应的调用参数
 * @return {@code ol}结果
 */
    public static GenerationStreamEvent toolResult(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(TOOL_RESULT)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    /**
 * 构建并返回结果。
 *
 * @param text {@code text} 对应的调用参数
 * @param data {@code data} 对应的调用参数
 * @return 结果
 */
    public static GenerationStreamEvent buildResult(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(BUILD_RESULT)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    /**
 * 返回生成阶段。
 *
 * @param text {@code text} 对应的调用参数
 * @param data {@code data} 对应的调用参数
 * @return 生成流事件
 */
    public static GenerationStreamEvent generationStage(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(GENERATION_STAGE)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    /**
 * 返回智能体事件。
 *
 * @param text {@code text} 对应的调用参数
 * @param data {@code data} 对应的调用参数
 * @return 生成流事件
 */
    public static GenerationStreamEvent agentEvent(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(AGENT_EVENT)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    /**
 * 返回生成错误。
 *
 * @param text {@code text} 对应的调用参数
 * @param data {@code data} 对应的调用参数
 * @return 生成流事件
 */
    public static GenerationStreamEvent generationError(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(GENERATION_ERROR)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    /**
 * 返回修复开始。
 *
 * @param text {@code text} 对应的调用参数
 * @param data {@code data} 对应的调用参数
 * @return 生成流事件
 */
    public static GenerationStreamEvent repairStart(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(REPAIR_START)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    /**
 * 返回生成{@code Stopped}。
 *
 * @param text {@code text} 对应的调用参数
 * @param data {@code data} 对应的调用参数
 * @return 生成流事件
 */
    public static GenerationStreamEvent generationStopped(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(GENERATION_STOPPED)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    /**
 * 返回开发服务器校验。
 *
 * @param text {@code text} 对应的调用参数
 * @param data {@code data} 对应的调用参数
 * @return 生成流事件
 */
    public static GenerationStreamEvent devServerValidation(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(DEV_SERVER_VALIDATION)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    /**
 * 返回首次预览就绪。
 *
 * @param text {@code text} 对应的调用参数
 * @param data {@code data} 对应的调用参数
 * @return 生成流事件
 */
    public static GenerationStreamEvent firstPreviewReady(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(FIRST_PREVIEW_READY)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }

    /** 数据库终态投影使用的稳定公开事件。 */
    public static GenerationStreamEvent taskTerminal(String text, Map<String, Object> data) {
        return GenerationStreamEvent.builder()
                .type(TASK_TERMINAL)
                .text(StrUtil.blankToDefault(text, ""))
                .data(data)
                .build();
    }
}
