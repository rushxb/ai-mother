package com.rush.rushaicodemother.service.feedback;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.GenerationFeedback;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.GenerationFeedbackVO;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackCommand;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackRepository;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackSignal;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackSignalPublisher;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultGenerationFeedbackService implements GenerationFeedbackService {

    private static final Pattern TASK_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern OUTCOME_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final int MAX_COMMENT_LENGTH = 2000;

    private final DurableGenerationTaskRepository taskRepository;
    private final GenerationFeedbackRepository feedbackRepository;
    private final GenerationFeedbackSignalPublisher feedbackSignalPublisher;

    @Override
    public GenerationFeedbackVO submit(GenerationFeedbackCommand command, User actor) {
        validateCommand(command);
        Long userId = actor == null ? null : actor.getId();
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        }
        DurableGenerationTaskRecord task = taskRepository.findByTaskId(command.taskId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "生成任务不存在"));
        if (!Objects.equals(task.userId(), userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权反馈该生成任务");
        }
        if (!task.terminal()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务结束后才能提交反馈");
        }

        LocalDateTime now = LocalDateTime.now();
        GenerationFeedback saved = feedbackRepository.upsert(GenerationFeedback.builder()
                .taskId(task.taskId())
                .appId(task.appId())
                .userId(userId)
                .rating(command.rating())
                .outcome(normalizeOutcome(command.outcome()))
                .comment(normalizeComment(command.comment()))
                .createTime(now)
                .updateTime(now)
                .isDelete(0)
                .build());
        if (saved == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成反馈保存失败");
        }
        publishFeedbackSignal(task, saved);
        return GenerationFeedbackVO.from(saved);
    }

    private void publishFeedbackSignal(DurableGenerationTaskRecord task, GenerationFeedback saved) {
        try {
            feedbackSignalPublisher.publish(new GenerationFeedbackSignal(
                    task.taskId(),
                    task.appId(),
                    task.tenantId(),
                    task.userId(),
                    task.status(),
                    saved.getRating(),
                    saved.getOutcome(),
                    saved.getComment()
            ));
        } catch (RuntimeException failure) {
            log.warn("Generation feedback signal publisher failed, taskId: {}, error: {}",
                    task.taskId(), LogExceptionSanitizer.sanitizeMessage(failure));
        }
    }

    private void validateCommand(GenerationFeedbackCommand command) {
        if (command == null || !TASK_ID_PATTERN.matcher(StrUtil.blankToDefault(command.taskId(), "")).matches()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成任务 ID 不合法");
        }
        if (command.rating() < 1 || command.rating() > 5) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "评分必须在 1 到 5 之间");
        }
        String outcome = normalizeOutcome(command.outcome());
        if (!OUTCOME_PATTERN.matcher(outcome).matches()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "反馈结果标签不合法");
        }
        if (normalizeComment(command.comment()).length() > MAX_COMMENT_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "反馈内容过长");
        }
    }

    private String normalizeOutcome(String outcome) {
        return StrUtil.blankToDefault(outcome, "unspecified").trim().toLowerCase();
    }

    private String normalizeComment(String comment) {
        return StrUtil.blankToDefault(comment, "").trim();
    }
}
