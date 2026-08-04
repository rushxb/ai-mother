package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.controller.support.GenerationSseEventMapper;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.dto.app.AppChatRequest;
import com.rush.rushaicodemother.model.dto.app.GenerationToolApprovalRequest;
import com.rush.rushaicodemother.model.dto.generation.GenerationFeedbackSubmitRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.GenerationFeedbackVO;
import com.rush.rushaicodemother.model.vo.GenerationTaskStatusVO;
import com.rush.rushaicodemother.model.vo.GenerationTaskSubmissionVO;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackCommand;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskControlService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskQueryService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSnapshot;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalService;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolContinuationScheduler;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalRecord;
import com.rush.rushaicodemother.ratelimiter.annotation.RateLimit;
import com.rush.rushaicodemother.ratelimiter.enums.RateLimitType;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.feedback.GenerationFeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** 面向任务的生成API；旧版应用程序范围的端点仍然是兼容性适配器。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/generation/tasks")
public class GenerationTaskController {

    private static final String TASK_ID_PATTERN = "[A-Za-z0-9_-]{1,128}";

    private final AppService appService;
    private final UserService userService;
    private final GenerationTaskQueryService generationTaskQueryService;
    private final GenerationTaskControlService generationTaskControlService;
    private final GenerationSseEventMapper generationSseEventMapper;
    private final ToolApprovalService toolApprovalService;
    private final GenerationToolContinuationScheduler toolContinuationScheduler;
    private final GenerationFeedbackService generationFeedbackService;

    /**
 * 校验并提交当前请求。
 *
 * @param request 请求参数
 * @param idempotencyKey 幂等键
 * @param servletRequest 当前 HTTP 请求
 * @return 统一封装的接口响应
 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60,
            message = "AI 生成任务请求过于频繁，请稍后再试")
    public BaseResponse<GenerationTaskSubmissionVO> submit(
            @Valid @RequestBody AppChatRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest
    ) {
        User actor = userService.getLoginUser(servletRequest);
        GenerationTaskResult result = appService.submitGeneration(
                request.getAppId(), request.getMessage(), actor, idempotencyKey);
        return ResultUtils.success(GenerationTaskSubmissionVO.from(result.submission()));
    }

    /**
 * 获取指定资源。
 *
 * @param taskId 任务编号
 * @param servletRequest 当前 HTTP 请求
 * @return 统一封装的接口响应
 */
    @GetMapping("/{taskId}")
    public BaseResponse<GenerationTaskStatusVO> get(
            @PathVariable @Pattern(regexp = TASK_ID_PATTERN) String taskId,
            HttpServletRequest servletRequest
    ) {
        User actor = userService.getLoginUser(servletRequest);
        return ResultUtils.success(GenerationTaskStatusVO.from(generationTaskQueryService.get(taskId, actor)));
    }

    @GetMapping("/by-app/{appId}/active")
    public BaseResponse<GenerationTaskStatusVO> getActiveForApp(
            @PathVariable @Positive Long appId,
            HttpServletRequest servletRequest
    ) {
        User actor = userService.getLoginUser(servletRequest);
        return ResultUtils.success(generationTaskQueryService.findLatestNonTerminalForApp(appId, actor)
                .map(GenerationTaskStatusVO::from)
                .orElse(null));
    }

    /**
 * 返回事件。
 *
 * @param taskId 任务编号
 * @param afterSequence 执行后序列
 * @param lastEventId 目标资源编号
 * @param servletRequest 当前 HTTP 请求
 * @return 异步响应式处理结果
 */
    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events(
            @PathVariable @Pattern(regexp = TASK_ID_PATTERN) String taskId,
            @RequestParam(name = "afterSequence", required = false) @PositiveOrZero Long afterSequence,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            HttpServletRequest servletRequest
    ) {
        User actor = userService.getLoginUser(servletRequest);
        long resumeAfter = resolveResumeSequence(afterSequence, lastEventId);
        return generationSseEventMapper.mapSequenced(
                generationTaskQueryService.sequencedEvents(taskId, resumeAfter, actor));
    }

    /**
 * 取消生成任务。
 *
 * @param taskId 任务编号
 * @param servletRequest 当前 HTTP 请求
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @PostMapping("/{taskId}/cancel")
    public BaseResponse<GenerationTaskStatusVO> cancel(
            @PathVariable @Pattern(regexp = TASK_ID_PATTERN) String taskId,
            HttpServletRequest servletRequest
    ) {
        User actor = userService.getLoginUser(servletRequest);
        return ResultUtils.success(GenerationTaskStatusVO.from(
                generationTaskControlService.cancel(taskId, actor)));
    }

    /**
 * 审批并返回工具动作。
 *
 * @param taskId 任务编号
 * @param request 请求参数
 * @param servletRequest 当前 HTTP 请求
 * @return 统一封装的接口响应
 */
    @PostMapping("/{taskId}/approvals")
    public BaseResponse<Boolean> approveToolAction(
            @PathVariable @Pattern(regexp = TASK_ID_PATTERN) String taskId,
            @Valid @RequestBody GenerationToolApprovalRequest request,
            HttpServletRequest servletRequest
    ) {
        User actor = userService.getLoginUser(servletRequest);
        generationTaskQueryService.get(taskId, actor);
        DestructiveToolAction action = DestructiveToolAction.fromValue(request.getAction());
        ToolApprovalRecord decision;
        if ("reject".equals(request.getDecision())) {
            decision = toolApprovalService.reject(
                    taskId, action, request.getApprovalId(), actor.getId());
        } else {
            decision = toolApprovalService.approve(
                    taskId, action, request.getApprovalId(), actor.getId());
        }
        toolContinuationScheduler.schedule(decision);
        return ResultUtils.success(true);
    }

    /**
 * 提交并返回反馈。
 *
 * @param taskId 任务编号
 * @param request 请求参数
 * @param servletRequest 当前 HTTP 请求
 * @return 统一封装的接口响应
 */
    @PostMapping("/{taskId}/feedback")
    public BaseResponse<GenerationFeedbackVO> submitFeedback(
            @PathVariable @Pattern(regexp = TASK_ID_PATTERN) String taskId,
            @Valid @RequestBody GenerationFeedbackSubmitRequest request,
            HttpServletRequest servletRequest
    ) {
        User actor = userService.getLoginUser(servletRequest);
        return ResultUtils.success(generationFeedbackService.submit(
                new GenerationFeedbackCommand(
                        taskId,
                        request.getRating(),
                        request.getOutcome(),
                        request.getComment()
                ),
                actor
        ));
    }

    /** 根据当前上下文解析{@code Resume}序列。 */
    private long resolveResumeSequence(Long afterSequence, String lastEventId) {
        long querySequence = afterSequence == null ? 0L : afterSequence;
        if (lastEventId == null || lastEventId.isBlank()) {
            return querySequence;
        }
        String normalized = lastEventId.trim();
        if (!normalized.matches("\\d{1,19}")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Last-Event-ID 必须是非负序号");
        }
        try {
            return Math.max(querySequence, Long.parseLong(normalized));
        } catch (NumberFormatException invalidSequence) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "Last-Event-ID 超出支持的序号范围",
                    invalidSequence
            );
        }
    }
}
