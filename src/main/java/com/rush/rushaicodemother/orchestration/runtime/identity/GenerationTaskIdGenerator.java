package com.rush.rushaicodemother.orchestration.runtime.identity;

/**
 * 为生成任务生成全局唯一的身份。
 *
 * <p>该抽象将任务身份所有权保留在编排持久性之外，因此
 * 当前的进程内运行时可以稍后被持久任务运行时替换，而无需更改
 * 准备、执行或快照模块。</p>
 */
public interface GenerationTaskIdGenerator {

    String nextId();
}
