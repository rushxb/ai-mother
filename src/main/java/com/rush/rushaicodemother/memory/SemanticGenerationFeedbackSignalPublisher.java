package com.rush.rushaicodemother.memory;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackSignal;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackSignalPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将明确的用户反馈存储在长期语义记忆中。
 *
 * <p>Feedback 被故意记录为不可信历史记录。稍后的上下文包组件可以
 * 将其视为偏好或改进提示，但不得将其视为直接指令。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticGenerationFeedbackSignalPublisher implements GenerationFeedbackSignalPublisher {

    private final GenerationSemanticMemoryService semanticMemoryService;

    /**
 * 发布当前处理结果或领域事件。
 *
 * @param signal 输入信号
 */
    @Override
    public void publish(GenerationFeedbackSignal signal) {
        if (signal == null || signal.tenantId() == null || signal.appId() == null
                || signal.userId() == null
                || StrUtil.isBlank(signal.taskId())) {
            return;
        }
        try {
            semanticMemoryService.rememberAsync(
                    signal.tenantId(),
                    signal.appId(),
                    signal.userId(),
                    signal.taskId(),
                    MemoryType.USER_FEEDBACK,
                    content(signal),
                    metadata(signal)
            );
        } catch (RuntimeException failure) {
            log.warn("Generation feedback signal persistence failed, taskId: {}, error: {}",
                    signal.taskId(), LogExceptionSanitizer.sanitizeMessage(failure));
        }
    }

    /** 返回内容。 */
    private String content(GenerationFeedbackSignal signal) {
        StringBuilder builder = new StringBuilder();
        builder.append("User feedback for generated application task.\n");
        builder.append("Task status: ")
                .append(signal.taskStatus() == null ? "unknown" : signal.taskStatus().getValue())
                .append('\n');
        builder.append("Rating: ").append(signal.rating()).append("/5\n");
        builder.append("Outcome: ").append(StrUtil.blankToDefault(signal.outcome(), "unspecified")).append('\n');
        if (StrUtil.isNotBlank(signal.comment())) {
            builder.append("User comment: ").append(signal.comment().trim()).append('\n');
        }
        if (signal.improvementCandidate()) {
            builder.append("Improvement candidate: true\n");
        }
        return builder.toString();
    }

    private Map<String, Object> metadata(GenerationFeedbackSignal signal) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("signalSource", "generation_feedback");
        metadata.put("rating", signal.rating());
        metadata.put("ratingBucket", ratingBucket(signal.rating()));
        metadata.put("outcome", StrUtil.blankToDefault(signal.outcome(), "unspecified"));
        metadata.put("taskStatus", signal.taskStatus() == null ? "unknown" : signal.taskStatus().getValue());
        metadata.put("improvementCandidate", signal.improvementCandidate());
        return metadata;
    }

    /** 返回{@code rating}{@code Bucket}。 */
    private String ratingBucket(int rating) {
        if (rating <= 2) {
            return "negative";
        }
        if (rating == 3) {
            return "neutral";
        }
        return "positive";
    }
}
