package com.rush.rushaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.PromptOptimizerServiceFactory;
import com.rush.rushaicodemother.application.app.AppAccessPolicy;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.dto.app.AppCodeFileSaveRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.ModelInvocationBillingMode;
import com.rush.rushaicodemother.model.enums.ModelInvocationPurpose;
import com.rush.rushaicodemother.model.vo.AppCodeFileContentVO;
import com.rush.rushaicodemother.model.vo.AppCodeFileTreeVO;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationTaskOrchestrator;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.experience.GenerationExperienceEventMapper;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskQueryService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskIdempotency;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskIdempotencyService;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.deployment.AppDeploymentService;
import com.rush.rushaicodemother.service.workspace.AppCodeWorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 应用服务层实现。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AppServiceImpl implements AppService {

    private final AppPersistenceService appPersistenceService;
    private final PromptOptimizerServiceFactory promptOptimizerServiceFactory;
    private final GenerationTaskOrchestrator generationTaskOrchestrator;
    private final GenerationEventPublisher generationEventPublisher;
    private final GenerationExperienceEventMapper generationExperienceEventMapper;
    private final GenerationTaskQueryService generationTaskQueryService;
    private final GenerationTaskIdempotencyService generationTaskIdempotencyService;
    private final AppDatabaseResourceService appDatabaseResourceService;
    private final AppCodeWorkspaceService appCodeWorkspaceService;
    private final AppDeploymentService appDeploymentService;
    private final AppAccessPolicy appAccessPolicy;

    /**
 * 返回对话{@code To}生成代码。
 *
 * @param appId 应用编号
 * @param message 消息内容
 * @param loginUser 当前登录用户
 * @return 异步响应式处理结果
 */
    @Override
    public Flux<GenerationStreamEvent> chatToGenCode(Long appId, String message, User loginUser) {
        return submitGeneration(appId, message, loginUser).contentFlux();
    }

    @Override
    public GenerationTaskResult submitGeneration(Long appId, String message, User loginUser) {
        return submitGeneration(appId, message, loginUser, null);
    }

    /**
 * 提交并返回生成。
 *
 * @param appId 应用编号
 * @param message 消息内容
 * @param loginUser 当前登录用户
 * @param idempotencyKey 幂等键
 * @return 应用服务{@code Impl}
 */
    @Override
    public GenerationTaskResult submitGeneration(Long appId,
                                                 String message,
                                                 User loginUser,
                                                 String idempotencyKey) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        GenerationTaskIdempotency idempotency = generationTaskIdempotencyService.resolve(
                idempotencyKey, appId, message);
        App app = getGenerationApp(appId, loginUser);
        return generationTaskOrchestrator.start(
                new GenerationTaskRequest(app, message, loginUser), idempotency);
    }

    private App getGenerationApp(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        App app = appPersistenceService.findActiveById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        appAccessPolicy.requireControlPermission(
                app, loginUser, GenerationControlPermission.TASK_SUBMIT,
                "无权限提交该应用的生成任务");
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        }
        return app;
    }

    /**
 * 获取并返回生成流。
 *
 * @param appId 应用编号
 * @param loginUser 当前登录用户
 * @return 异步响应式处理结果
 */
    @Override
    public Flux<GenerationStreamEvent> getGenerationStream(Long appId, User loginUser) {
        App app = getControlApp(
                appId, loginUser, GenerationControlPermission.TASK_QUERY,
                "无权限查看该应用的生成任务");
        Flux<GenerationStreamEvent> recentStructuredEvents = Flux.fromIterable(generationEventPublisher.recent(app.getId()))
                .handle((event, sink) -> generationExperienceEventMapper.map(event).ifPresent(sink::next));
        return recentStructuredEvents.concatWith(
                generationTaskQueryService.eventsForLatestNonTerminalAppTask(app.getId(), loginUser)
        );
    }

    /**
 * 停止生成。
 *
 * @param appId 应用编号
 * @param loginUser 当前登录用户
 */
    @Override
    public void stopGeneration(Long appId, User loginUser) {
        App app = getControlApp(
                appId, loginUser, GenerationControlPermission.TASK_CANCEL,
                "无权限取消该应用的生成任务");
        generationTaskOrchestrator.stop(app.getId(), loginUser);
    }

    /**
 * 优化并返回提示词。
 *
 * @param prompt 提示词
 * @param loginUser 当前登录用户
 * @return 处理后的应用服务{@code Impl}文本
 */
    @Override
    public String optimizePrompt(String prompt, User loginUser) {
        ThrowUtils.throwIf(StrUtil.isBlank(prompt), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        ThrowUtils.throwIf(prompt.length() > 1000, ErrorCode.PARAMS_ERROR, "提示词不能超过 1000 字");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        MonitorContext previousContext = MonitorContextHolder.getContext();
        MonitorContextHolder.setContext(
                MonitorContext.builder()
                            .userId(loginUser.getId().toString())
                            .taskId("prompt-optimize:" + UUID.randomUUID())
                            .invocationPurpose(ModelInvocationPurpose.PROMPT_OPTIMIZATION)
                            .billingMode(ModelInvocationBillingMode.EXEMPT)
                            .billingExemptionReason("interactive_free_tier")
                            .build()
        );
        try {
            String optimizedPrompt = promptOptimizerServiceFactory.promptOptimizerService().optimizePrompt(prompt);
            ThrowUtils.throwIf(StrUtil.isBlank(optimizedPrompt), ErrorCode.OPERATION_ERROR, "提示词优化失败");
            return optimizedPrompt.trim();
        } finally {
            if (previousContext == null) {
                MonitorContextHolder.clearContext();
            } else {
                MonitorContextHolder.setContext(previousContext);
            }
        }
    }

    /**
 * 返回部署应用。
 *
 * @param appId 应用编号
 * @param loginUser 当前登录用户
 * @return 处理后的应用服务{@code Impl}文本
 */
    @Override
    public String deployApp(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        return appDeploymentService.deploy(app);
    }

    /**
 * 列出符合条件的应用代码文件。
 *
 * @param appId 应用编号
 * @param loginUser 当前登录用户
 * @return 应用代码文件集合
 */
    @Override
    public List<AppCodeFileTreeVO> listAppCodeFiles(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        return appCodeWorkspaceService.listFiles(app);
    }

    @Override
    public AppCodeFileContentVO getAppCodeFileContent(Long appId, String filePath, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        return appCodeWorkspaceService.readFile(app, filePath);
    }

    /**
 * 保存应用代码文件。
 *
 * @param saveRequest {@code saveRequest} 对应的调用参数
 * @param loginUser 当前登录用户
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public Boolean saveAppCodeFile(AppCodeFileSaveRequest saveRequest, User loginUser) {
        ThrowUtils.throwIf(saveRequest == null, ErrorCode.PARAMS_ERROR);
        App app = getOwnedApp(saveRequest.getAppId(), loginUser);
        appCodeWorkspaceService.saveFile(app, saveRequest.getFilePath(), saveRequest.getContent());
        return true;
    }

    /**
 * 同步并返回应用部署。
 *
 * @param appId 应用编号
 * @param loginUser 当前登录用户
 * @return 处理后的应用服务{@code Impl}文本
 */
    @Override
    public String syncAppDeployment(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        return appDeploymentService.synchronize(app);
    }

    /**
 * 启用数据库。
 *
 * @param appId 应用编号
 * @param loginUser 当前登录用户
 * @return 数据库
 */
    @Override
    public AppDatabaseResourceVO enableDatabase(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        return appDatabaseResourceService.enableDatabase(app);
    }

    private App getOwnedApp(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        App app = appPersistenceService.findActiveById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        appAccessPolicy.requireOwner(app, loginUser, "无权限访问该应用代码");
        return app;
    }

    private App getControlApp(Long appId,
                              User actor,
                              GenerationControlPermission permission,
                              String deniedMessage) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        App app = appPersistenceService.findActiveById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        appAccessPolicy.requireControlPermission(app, actor, permission, deniedMessage);
        return app;
    }

}
