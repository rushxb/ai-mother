package com.rush.rushaicodemother.controller.app;

import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.controller.support.GenerationSseEventMapper;
import com.rush.rushaicodemother.model.dto.app.AppChatRequest;
import com.rush.rushaicodemother.model.dto.app.AppDatabaseEnableRequest;
import com.rush.rushaicodemother.model.dto.app.AppStopRequest;
import com.rush.rushaicodemother.model.dto.app.PromptOptimizeRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlAccess;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditResource;
import com.rush.rushaicodemother.ratelimiter.annotation.RateLimit;
import com.rush.rushaicodemother.ratelimiter.enums.RateLimitType;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** 应用生成与提示词能力控制器。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app")
public class AppGenerationController {

    private static final String PROMPT_OPTIMIZATION_RATE_LIMIT_KEY = "prompt:optimize";
    private static final int PROMPT_OPTIMIZATION_RATE_LIMIT = 30;
    private static final int PROMPT_OPTIMIZATION_RATE_INTERVAL_SECONDS = 3600;

    private final AppService appService;
    private final UserService userService;
    private final GenerationSseEventMapper generationSseEventMapper;

    /**
 * 优化并返回提示词。
 *
 * @param request 请求参数
 * @param servletRequest 当前 HTTP 请求
 * @return 统一封装的接口响应
 */
    @PostMapping("/optimize/prompt")
    @RateLimit(
            key = PROMPT_OPTIMIZATION_RATE_LIMIT_KEY,
            limitType = RateLimitType.USER,
            rate = PROMPT_OPTIMIZATION_RATE_LIMIT,
            rateInterval = PROMPT_OPTIMIZATION_RATE_INTERVAL_SECONDS,
            message = "提示词优化请求过于频繁，请稍后再试"
    )
    public BaseResponse<String> optimizePrompt(@Valid @RequestBody PromptOptimizeRequest request,
                                               HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(appService.optimizePrompt(request.getPrompt(), loginUser));
    }

    /**
 * 启动对话{@code To}生成代码。
 *
 * @param request 请求参数
 * @param servletRequest 当前 HTTP 请求
 * @return 统一封装的接口响应
 */
    @PostMapping("/chat/gen/code")
    @GenerationControlAccess(
            value = GenerationControlPermission.TASK_SUBMIT,
            auditResource = GenerationControlAuditResource.APP,
            auditResourceId = "#p0.appId")
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60,
            message = "AI 对话请求过于频繁，请稍后再试")
    public BaseResponse<Boolean> startChatToGenCode(@Valid @RequestBody AppChatRequest request,
                                                    HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        appService.chatToGenCode(request.getAppId(), request.getMessage(), loginUser);
        return ResultUtils.success(true);
    }

    /**
 * 停止对话{@code To}生成代码。
 *
 * @param request 请求参数
 * @param servletRequest 当前 HTTP 请求
 * @return 统一封装的接口响应
 */
    @PostMapping("/chat/gen/code/stop")
    @GenerationControlAccess(
            value = GenerationControlPermission.TASK_CANCEL,
            auditResource = GenerationControlAuditResource.APP,
            auditResourceId = "#p0.appId")
    public BaseResponse<Boolean> stopChatToGenCode(@Valid @RequestBody AppStopRequest request,
                                                   HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        appService.stopGeneration(request.getAppId(), loginUser);
        return ResultUtils.success(true);
    }

    /**
 * 返回订阅对话{@code To}生成代码。
 *
 * @param appId 应用编号
 * @param servletRequest 当前 HTTP 请求
 * @return 异步响应式处理结果
 */
    @GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @GenerationControlAccess(
            value = GenerationControlPermission.TASK_QUERY,
            auditResource = GenerationControlAuditResource.APP,
            auditResourceId = "#p0")
    public Flux<ServerSentEvent<String>> subscribeChatToGenCode(@RequestParam @Positive Long appId,
                                                                 HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        Flux<GenerationStreamEvent> contentFlux = appService.getGenerationStream(appId, loginUser);
        return generationSseEventMapper.map(contentFlux);
    }

    /**
 * 启用数据库。
 *
 * @param request 请求参数
 * @param servletRequest 当前 HTTP 请求
 * @return 统一封装的接口响应
 */
    @PostMapping("/database/enable")
    public BaseResponse<AppDatabaseResourceVO> enableDatabase(
            @Valid @RequestBody AppDatabaseEnableRequest request,
            HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(appService.enableDatabase(request.getAppId(), loginUser));
    }
}
