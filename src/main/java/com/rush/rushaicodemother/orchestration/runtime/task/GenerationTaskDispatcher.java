package com.rush.rushaicodemother.orchestration.runtime.task;

/**
 * 将已持久化的生成命令分派到当前工作传输。
 *
 * <p>adapter 必须用返回值区分已接纳、无需重复处理和暂缓重试；瞬时容量或
 * 传输不可用不得通过异常把已持久化任务错误终态化。</p>
 */
public interface GenerationTaskDispatcher {

    GenerationTaskDispatchResult dispatch(String taskId);
}
