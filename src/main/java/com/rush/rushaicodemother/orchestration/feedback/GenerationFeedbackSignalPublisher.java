package com.rush.rushaicodemother.orchestration.feedback;

/**
 * 发布用户反馈作为人工智能改进信号。
 *
 * <p> 实施必须尽力而为：下游内存或分析中断不得导致
 * 已有的反馈提交失败。</p>
 */
public interface GenerationFeedbackSignalPublisher {

    void publish(GenerationFeedbackSignal signal);
}
