package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.controller.support.GenerationSseEventMapper;
import com.rush.rushaicodemother.model.dto.app.AppChatRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.GenerationTaskStatusVO;
import com.rush.rushaicodemother.model.vo.GenerationTaskSubmissionVO;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskControlService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskQueryService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSnapshot;
import com.rush.rushaicodemother.ratelimiter.annotation.RateLimit;
import com.rush.rushaicodemother.ratelimiter.enums.RateLimitType;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** Task-oriented generation API; legacy app-scoped endpoints remain compatibility adapters. */
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

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60,
            message = "AI 生成任务请求过于频繁，请稍后再试")
    public BaseResponse<GenerationTaskSubmissionVO> submit(
            @Valid @RequestBody AppChatRequest request,
            HttpServletRequest servletRequest
    ) {
        User actor = userService.getLoginUser(servletRequest);
        GenerationTaskResult result = appService.submitGeneration(request.getAppId(), request.getMessage(), actor);
        GenerationTaskSnapshot snapshot = generationTaskQueryService.get(result.taskId(), actor);
        return ResultUtils.success(GenerationTaskSubmissionVO.from(snapshot));
    }

    @GetMapping("/{taskId}")
    public BaseResponse<GenerationTaskStatusVO> get(
            @PathVariable @Pattern(regexp = TASK_ID_PATTERN) String taskId,
            HttpServletRequest servletRequest
    ) {
        User actor = userService.getLoginUser(servletRequest);
        return ResultUtils.success(GenerationTaskStatusVO.from(generationTaskQueryService.get(taskId, actor)));
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events(
            @PathVariable @Pattern(regexp = TASK_ID_PATTERN) String taskId,
            HttpServletRequest servletRequest
    ) {
        User actor = userService.getLoginUser(servletRequest);
        return generationSseEventMapper.map(generationTaskQueryService.events(taskId, actor));
    }

    @PostMapping("/{taskId}/cancel")
    public BaseResponse<GenerationTaskStatusVO> cancel(
            @PathVariable @Pattern(regexp = TASK_ID_PATTERN) String taskId,
            HttpServletRequest servletRequest
    ) {
        User actor = userService.getLoginUser(servletRequest);
        return ResultUtils.success(GenerationTaskStatusVO.from(
                generationTaskControlService.cancel(taskId, actor)));
    }
}
