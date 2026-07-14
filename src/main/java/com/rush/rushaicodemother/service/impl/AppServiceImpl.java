package com.rush.rushaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.PromptOptimizerServiceFactory;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.dto.app.AppCodeFileSaveRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.vo.AppCodeFileContentVO;
import com.rush.rushaicodemother.model.vo.AppCodeFileTreeVO;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationTaskOrchestrator;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEvent;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeService;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.deployment.AppDeploymentService;
import com.rush.rushaicodemother.service.workspace.AppCodeWorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 应用服务层实现。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AppServiceImpl implements AppService {

    private final UserCreditService userCreditService;
    private final AppPersistenceService appPersistenceService;
    private final AiModelRuntimeService aiModelRuntimeService;
    private final PromptOptimizerServiceFactory promptOptimizerServiceFactory;
    private final GenerationTaskOrchestrator generationTaskOrchestrator;
    private final GenerationEventPublisher generationEventPublisher;
    private final AppDatabaseResourceService appDatabaseResourceService;
    private final AppCodeWorkspaceService appCodeWorkspaceService;
    private final AppDeploymentService appDeploymentService;

    @Override
    public Flux<GenerationStreamEvent> chatToGenCode(Long appId, String message, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        App app = getGenerationApp(appId, loginUser);
        aiModelRuntimeService.ensureGenerationModelsConfigured();
        userCreditService.ensureHasCredit(loginUser.getId());
        enableDatabaseForGenerationIfNeeded(app, message);
        generationEventPublisher.clearRecent(app.getId());
        GenerationTaskResult taskResult = generationTaskOrchestrator.start(new GenerationTaskRequest(app, message, loginUser));
        return taskResult.contentFlux();
    }

    private App getGenerationApp(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        App app = appPersistenceService.findActiveById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!Objects.equals(app.getUserId(), loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        }
        return app;
    }

    private void enableDatabaseForGenerationIfNeeded(App app, String message) {
        if (appDatabaseResourceService.shouldEnableForPrompt(message)) {
            appDatabaseResourceService.enableDatabase(app);
        }
    }

    @Override
    public Flux<GenerationStreamEvent> getGenerationStream(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        Flux<GenerationStreamEvent> recentStructuredEvents = Flux.fromIterable(generationEventPublisher.recent(app.getId()))
                .map(this::toGenerationStreamEvent);
        return recentStructuredEvents.concatWith(generationTaskOrchestrator.getStream(app.getId()));
    }

    private GenerationStreamEvent toGenerationStreamEvent(GenerationEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (event.data() != null) {
            data.putAll(event.data());
        }
        data.put("eventType", event.type() == null ? "" : event.type().getValue());
        data.put("occurredAt", event.occurredAt() == null ? "" : event.occurredAt().toString());
        return GenerationStreamEvent.agentEvent(StrUtil.blankToDefault(event.message(), ""), data);
    }

    @Override
    public void stopGeneration(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        generationTaskOrchestrator.stop(app.getId(), loginUser);
    }

    @Override
    public String optimizePrompt(String prompt, User loginUser) {
        ThrowUtils.throwIf(StrUtil.isBlank(prompt), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        ThrowUtils.throwIf(prompt.length() > 1000, ErrorCode.PARAMS_ERROR, "提示词不能超过 1000 字");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        MonitorContextHolder.setContext(
                MonitorContext.builder()
                            .userId(loginUser.getId().toString())
                            .appId("prompt_optimize")
                            .taskId("prompt_optimize")
                            .build()
        );
        try {
            String optimizedPrompt = promptOptimizerServiceFactory.promptOptimizerService().optimizePrompt(prompt);
            ThrowUtils.throwIf(StrUtil.isBlank(optimizedPrompt), ErrorCode.OPERATION_ERROR, "提示词优化失败");
            return optimizedPrompt.trim();
        } finally {
            MonitorContextHolder.clearContext();
        }
    }

    @Override
    public String deployApp(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        return appDeploymentService.deploy(app);
    }

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

    @Override
    public Boolean saveAppCodeFile(AppCodeFileSaveRequest saveRequest, User loginUser) {
        ThrowUtils.throwIf(saveRequest == null, ErrorCode.PARAMS_ERROR);
        App app = getOwnedApp(saveRequest.getAppId(), loginUser);
        appCodeWorkspaceService.saveFile(app, saveRequest.getFilePath(), saveRequest.getContent());
        return true;
    }

    @Override
    public String syncAppDeployment(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        return appDeploymentService.synchronize(app);
    }

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
        if (!Objects.equals(app.getUserId(), loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用代码");
        }
        return app;
    }

}
