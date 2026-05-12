package com.yupi.yuaicodemother.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.yupi.yuaicodemother.ai.AiCodeGenTypeRoutingService;
import com.yupi.yuaicodemother.ai.AiCodeGenTypeRoutingServiceFactory;
import com.yupi.yuaicodemother.ai.AppNameGeneratorService;
import com.yupi.yuaicodemother.ai.PromptOptimizerService;
import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.core.AiCodeGeneratorFacade;
import com.yupi.yuaicodemother.core.builder.VueProjectBuilder;
import com.yupi.yuaicodemother.core.error.GenerationErrorClassifier;
import com.yupi.yuaicodemother.core.handler.GenerationStreamEvent;
import com.yupi.yuaicodemother.core.handler.StreamHandlerExecutor;
import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import com.yupi.yuaicodemother.exception.ThrowUtils;
import com.yupi.yuaicodemother.model.dto.app.AppAddRequest;
import com.yupi.yuaicodemother.model.dto.app.AppCodeFileSaveRequest;
import com.yupi.yuaicodemother.model.dto.app.AppQueryRequest;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.mapper.AppMapper;
import com.yupi.yuaicodemother.model.entity.User;
import com.yupi.yuaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.model.vo.AppCodeFileContentVO;
import com.yupi.yuaicodemother.model.vo.AppCodeFileTreeVO;
import com.yupi.yuaicodemother.model.vo.AppDatabaseResourceVO;
import com.yupi.yuaicodemother.model.vo.AppVO;
import com.yupi.yuaicodemother.model.vo.UserVO;
import com.yupi.yuaicodemother.monitor.MonitorContext;
import com.yupi.yuaicodemother.monitor.MonitorContextHolder;
import com.yupi.yuaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.yupi.yuaicodemother.orchestration.GenerationOrchestrationRequest;
import com.yupi.yuaicodemother.orchestration.GenerationOrchestrationResult;
import com.yupi.yuaicodemother.orchestration.GenerationOrchestrator;
import com.yupi.yuaicodemother.orchestration.artifact.DiffSummary;
import com.yupi.yuaicodemother.orchestration.artifact.ChangePlan;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationCommitResult;
import com.yupi.yuaicodemother.orchestration.artifact.PatchResult;
import com.yupi.yuaicodemother.orchestration.artifact.QualityGateResult;
import com.yupi.yuaicodemother.orchestration.review.OrphanFileReviewService;
import com.yupi.yuaicodemother.orchestration.patch.GenerationPatchResultService;
import com.yupi.yuaicodemother.orchestration.snapshot.GenerationCommitService;
import com.yupi.yuaicodemother.orchestration.snapshot.GenerationDiffSummaryService;
import com.yupi.yuaicodemother.orchestration.snapshot.GenerationRollbackRestoreService;
import com.yupi.yuaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.yupi.yuaicodemother.service.AppService;
import com.yupi.yuaicodemother.service.AppDatabaseResourceService;
import com.yupi.yuaicodemother.service.ChatHistoryService;
import com.yupi.yuaicodemother.service.ScreenshotService;
import com.yupi.yuaicodemother.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.File;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 应用 服务层实现。
 *
 *
 */
@Service
@Slf4j
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    private static final long MAX_EDIT_FILE_SIZE = 1024 * 1024;
    private static final int MAX_MODEL_CONTEXT_FILE_CHARS = 12000;
    private static final int MAX_GENERATION_SNAPSHOT_CHARS = 20000;
    private static final long GENERATION_SNAPSHOT_UPDATE_INTERVAL_MILLIS = 1000;
    private static final int MAX_GENERATION_REPLAY_EVENTS = 500;
    private static final int MAX_AUTO_REPAIR_ROUNDS = 1;
    private static final int MAX_PROJECT_INDEX_FILES = 80;
    private static final int MAX_FILE_TREE_DEPTH = 8;
    private static final int FALLBACK_APP_NAME_LENGTH = 12;
    private static final int MAX_APP_NAME_LENGTH = 16;

    private static final Set<String> HIDDEN_FILE_NAMES = Set.of(
            ".git", ".idea", "node_modules", "dist", "target", ".DS_Store"
    );

    private static final Set<String> EDITABLE_EXTENSIONS = Set.of(
            "html", "css", "js", "ts", "jsx", "tsx", "vue", "json", "md", "txt", "xml", "svg", "yml", "yaml", "go", "sql", "mod", "sum"
    );

    @Value("${code.deploy-host:http://localhost:8088}")
    private String deployHost;

    @Resource
    private UserService userService;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private StreamHandlerExecutor streamHandlerExecutor;

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private ScreenshotService screenshotService;

    @Resource
    private AiCodeGenTypeRoutingServiceFactory aiCodeGenTypeRoutingServiceFactory;

    @Resource
    private PromptOptimizerService promptOptimizerService;

    @Resource
    private AppNameGeneratorService appNameGeneratorService;

    @Resource
    private GenerationOrchestrator generationOrchestrator;

    @Resource
    private AppDatabaseResourceService appDatabaseResourceService;

    @Resource
    private GenerationDiffSummaryService generationDiffSummaryService;

    @Resource
    private GenerationRollbackRestoreService generationRollbackRestoreService;

    @Resource
    private GenerationPatchResultService generationPatchResultService;

    @Resource
    private GenerationCommitService generationCommitService;

    @Resource
    private GenerationOrchestrationMetricsCollector generationOrchestrationMetricsCollector;

    @Resource
    private GenerationToolExecutionContextService generationToolExecutionContextService;

    @Resource
    private OrphanFileReviewService orphanFileReviewService;

    private final Map<Long, Object> generationLocks = new ConcurrentHashMap<>();
    private final Map<Long, GenerationSession> activeGenerationSessions = new ConcurrentHashMap<>();

    @Override
    public Flux<GenerationStreamEvent> chatToGenCode(Long appId, String message, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        App app = getGenerationApp(appId, loginUser);
        enableDatabaseForGenerationIfNeeded(app, message);
        GenerationPreparation preparation = prepareGeneration(app, message);
        GenerationSession session = openGenerationSession(appId, message, loginUser, preparation);
        startGenerationTask(appId, loginUser, preparation, session);
        return session.asFlux();
    }

    private App getGenerationApp(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!app.getUserId().equals(loginUser.getId())) {
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

    private GenerationSession openGenerationSession(Long appId,
                                                    String message,
                                                    User loginUser,
                                                    GenerationPreparation preparation) {
        GenerationSession session;
        synchronized (getGenerationLock(appId)) {
            ThrowUtils.throwIf(activeGenerationSessions.containsKey(appId), ErrorCode.OPERATION_ERROR, "当前应用正在生成中，请稍后再试");
            resetResidualGenerationState(this.getById(appId));
            chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());
            if (preparation.upgradeRequired()) {
                switchAppCodeGenType(appId, preparation.targetType());
            }
            markGenerationStarted(appId, preparation.generatingStage());
            updateGenerationPhase(appId, AppConstant.GENERATING_STAGE_AGENT, "智能体正在分析需求并规划生成策略...");
            session = new GenerationSession(preparation);
            activeGenerationSessions.put(appId, session);
        }
        return session;
    }

    @Override
    public Flux<GenerationStreamEvent> getGenerationStream(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        GenerationSession session = activeGenerationSessions.get(app.getId());
        ThrowUtils.throwIf(session == null, ErrorCode.OPERATION_ERROR, "当前应用没有进行中的生成任务");
        return session.asFlux();
    }

    @Override
    public void stopGeneration(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        GenerationSession session = activeGenerationSessions.get(app.getId());
        ThrowUtils.throwIf(session == null || !session.isActive(), ErrorCode.OPERATION_ERROR, "当前应用没有进行中的生成任务");
        session.cancel();
        markGenerationFinished(app.getId());
        session.emitStopped();
        completeGenerationSession(session, session.preparation(), "cancelled");
        activeGenerationSessions.remove(app.getId(), session);
        generationToolExecutionContextService.clearContext(app.getId());
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
            String optimizedPrompt = promptOptimizerService.optimizePrompt(prompt);
            ThrowUtils.throwIf(StrUtil.isBlank(optimizedPrompt), ErrorCode.OPERATION_ERROR, "提示词优化失败");
            return optimizedPrompt.trim();
        } finally {
            MonitorContextHolder.clearContext();
        }
    }

    @Override
    public Long createApp(AppAddRequest appAddRequest, User loginUser) {
        // 参数校验
        String initPrompt = appAddRequest.getInitPrompt();
        ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");
        // 构造入库对象
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        app.setAppName(generateAppName(initPrompt));
        // 使用 AI 智能选择代码生成类型（多例模式）
        AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService = aiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService();
        CodeGenTypeEnum selectedCodeGenType = aiCodeGenTypeRoutingService.routeCodeGenType(initPrompt);
        app.setCodeGenType(selectedCodeGenType.getValue());
        // 插入数据库
        boolean result = this.save(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        log.info("应用创建成功，ID: {}, 类型: {}", app.getId(), selectedCodeGenType.getValue());
        return app.getId();
    }

    private String generateAppName(String initPrompt) {
        try {
            String generatedName = appNameGeneratorService.generateAppName(initPrompt);
            String normalizedName = normalizeAppName(generatedName);
            if (StrUtil.isNotBlank(normalizedName)) {
                return normalizedName;
            }
        } catch (Exception e) {
            log.warn("AI 生成应用标题失败，使用兜底标题，prompt={}", StrUtil.sub(initPrompt, 0, 60), e);
        }
        return fallbackAppName(initPrompt);
    }

    private String normalizeAppName(String appName) {
        if (StrUtil.isBlank(appName)) {
            return null;
        }
        String normalized = StrUtil.trim(appName)
                .replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("^(标题|应用名|应用名称)\\s*[:：]\\s*", "")
                .replaceAll("\\s+", " ")
                .replaceAll("^[\"'“”‘’《》【】\\s]+", "")
                .replaceAll("[\"'“”‘’《》【】\\s]+$", "");
        if (StrUtil.isBlank(normalized)) {
            return null;
        }
        return StrUtil.sub(normalized, 0, Math.min(normalized.length(), MAX_APP_NAME_LENGTH));
    }

    private String fallbackAppName(String initPrompt) {
        String normalizedPrompt = StrUtil.trim(initPrompt)
                .replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ");
        if (StrUtil.isBlank(normalizedPrompt)) {
            return "未命名应用";
        }
        return StrUtil.sub(normalizedPrompt, 0, Math.min(normalizedPrompt.length(), FALLBACK_APP_NAME_LENGTH));
    }

    @Override
    public String deployApp(Long appId, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 权限校验，仅本人可以部署自己的应用
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限部署该应用");
        }
        // 4. 检查是否已有 deployKey
        String deployKey = app.getDeployKey();
        // 如果没有，则生成 6 位 deployKey（字母 + 数字）
        if (StrUtil.isBlank(deployKey)) {
            deployKey = RandomUtil.randomString(6);
        }
        // 5. 获取代码生成类型，获取原始代码生成路径（应用访问目录）
        String codeGenType = app.getCodeGenType();
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;
        // 6. 检查路径是否存在
        File sourceDir = new File(sourceDirPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用代码路径不存在，请先生成应用");
        }
        // 7. Vue 项目特殊处理：执行构建
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == CodeGenTypeEnum.BACKEND_PROJECT) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "后端工程暂不支持静态部署，请下载后本地运行或后续接入容器化部署");
        }
        if (codeGenTypeEnum == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "全栈工程已预留容器化部署上下文，暂不支持静态部署或自动启动后端服务");
        }
        if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
            // Vue 项目需要构建
            VueProjectBuilder.BuildResult buildResult = vueProjectBuilder.buildProjectWithResult(sourceDirPath);
            ThrowUtils.throwIf(!buildResult.success(), ErrorCode.SYSTEM_ERROR, buildResult.toFailureSummary());
            // 检查 dist 目录是否存在
            File distDir = new File(sourceDirPath, "dist");
            ThrowUtils.throwIf(!distDir.exists(), ErrorCode.SYSTEM_ERROR, "Vue 项目构建完成但未生成 dist 目录");
            // 构建完成后，需要将构建后的文件复制到部署目录
            sourceDir = distDir;
        }
        // 8. 复制文件到部署目录
        String deployDirPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;
        try {
            FileUtil.copyContent(sourceDir, new File(deployDirPath), true);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用部署失败：" + e.getMessage());
        }
        // 9. 更新数据库
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setDeployKey(deployKey);
        updateApp.setDeployedTime(LocalDateTime.now());
        boolean updateResult = this.updateById(updateApp);
        ThrowUtils.throwIf(!updateResult, ErrorCode.OPERATION_ERROR, "更新应用部署信息失败");
        // 10. 构建应用访问 URL
        String appDeployUrl = String.format("%s/%s/", deployHost, deployKey);        // 11. 异步生成截图并且更新应用封面
        generateAppScreenshotAsync(appId, appDeployUrl);
        return appDeployUrl;
    }

    @Override
    public List<AppCodeFileTreeVO> listAppCodeFiles(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        File rootDir;
        try {
            rootDir = getCodeRootDir(app);
        } catch (BusinessException e) {
            if (e.getCode() == ErrorCode.NOT_FOUND_ERROR.getCode()) {
                return new ArrayList<>();
            }
            throw e;
        }
        File[] files = rootDir.listFiles(file -> !shouldHideFile(file));
        if (files == null) {
            return new ArrayList<>();
        }
        return Arrays.stream(files)
                .sorted(fileComparator())
                .map(file -> buildFileTreeNode(rootDir, file, 1))
                .collect(Collectors.toList());
    }

    @Override
    public AppCodeFileContentVO getAppCodeFileContent(Long appId, String filePath, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        File rootDir = getCodeRootDir(app);
        File targetFile = resolveCodeFile(rootDir, filePath);
        ThrowUtils.throwIf(!targetFile.exists() || !targetFile.isFile(), ErrorCode.NOT_FOUND_ERROR, "文件不存在");
        ThrowUtils.throwIf(shouldHideFile(targetFile), ErrorCode.NO_AUTH_ERROR, "禁止访问该文件");
        ThrowUtils.throwIf(targetFile.length() > MAX_EDIT_FILE_SIZE, ErrorCode.OPERATION_ERROR, "文件过大，不支持在线编辑");
        boolean editable = isEditableFile(targetFile);
        ThrowUtils.throwIf(!editable, ErrorCode.OPERATION_ERROR, "该文件类型不支持在线预览编辑");
        AppCodeFileContentVO contentVO = new AppCodeFileContentVO();
        contentVO.setPath(normalizeRelativePath(rootDir, targetFile));
        contentVO.setName(targetFile.getName());
        contentVO.setContent(FileUtil.readString(targetFile, StandardCharsets.UTF_8));
        contentVO.setSize(targetFile.length());
        contentVO.setEditable(true);
        return contentVO;
    }

    @Override
    public Boolean saveAppCodeFile(AppCodeFileSaveRequest saveRequest, User loginUser) {
        ThrowUtils.throwIf(saveRequest == null, ErrorCode.PARAMS_ERROR);
        App app = getOwnedApp(saveRequest.getAppId(), loginUser);
        File rootDir = getCodeRootDir(app);
        String content = saveRequest.getContent();
        ThrowUtils.throwIf(content == null, ErrorCode.PARAMS_ERROR, "文件内容不能为空");
        ThrowUtils.throwIf(content.getBytes(StandardCharsets.UTF_8).length > MAX_EDIT_FILE_SIZE,
                ErrorCode.OPERATION_ERROR, "文件内容过大，不支持在线保存");
        File targetFile = resolveCodeFile(rootDir, saveRequest.getFilePath());
        ThrowUtils.throwIf(!targetFile.exists() || !targetFile.isFile(), ErrorCode.NOT_FOUND_ERROR, "文件不存在");
        ThrowUtils.throwIf(shouldHideFile(targetFile), ErrorCode.NO_AUTH_ERROR, "禁止修改该文件");
        ThrowUtils.throwIf(!isEditableFile(targetFile), ErrorCode.OPERATION_ERROR, "该文件类型不支持在线编辑");
        String originalContent = FileUtil.readString(targetFile, StandardCharsets.UTF_8);
        FileUtil.writeString(content, targetFile, StandardCharsets.UTF_8);
        try {
            rebuildIfVueProject(app, rootDir);
        } catch (BusinessException e) {
            rollbackSavedFile(app, rootDir, targetFile, originalContent);
            throw new BusinessException(e.getCode(), "保存失败，代码未通过编译，已自动回退到上一次可用版本");
        } catch (Exception e) {
            rollbackSavedFile(app, rootDir, targetFile, originalContent);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存失败，代码未通过编译，已自动回退到上一次可用版本");
        }
        return true;
    }

    @Override
    public String syncAppDeployment(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        ThrowUtils.throwIf(StrUtil.isBlank(app.getDeployKey()), ErrorCode.OPERATION_ERROR, "应用尚未部署，请先部署后再同步");
        return deployApp(appId, loginUser);
    }

    @Override
    public AppDatabaseResourceVO enableDatabase(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        return appDatabaseResourceService.getResourceVO(appDatabaseResourceService.enableDatabase(app));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copyApp(Long sourceAppId, User loginUser) {
        ThrowUtils.throwIf(sourceAppId == null || sourceAppId <= 0, ErrorCode.PARAMS_ERROR, "源应用 ID 错误");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        App sourceApp = this.getById(sourceAppId);
        ThrowUtils.throwIf(sourceApp == null, ErrorCode.NOT_FOUND_ERROR, "源应用不存在");

        App targetApp = new App();
        targetApp.setAppName(sourceApp.getAppName());
        targetApp.setCover(sourceApp.getCover());
        targetApp.setInitPrompt(sourceApp.getInitPrompt());
        targetApp.setCodeGenType(sourceApp.getCodeGenType());
        targetApp.setPriority(AppConstant.DEFAULT_APP_PRIORITY);
        targetApp.setUserId(loginUser.getId());
        boolean saved = this.save(targetApp);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "复制应用失败");

        copyGeneratedCodeDir(sourceApp, targetApp);
        boolean historyCopied = chatHistoryService.copyByAppId(sourceAppId, targetApp.getId(), loginUser.getId());
        ThrowUtils.throwIf(!historyCopied, ErrorCode.OPERATION_ERROR, "复制应用对话失败");
        return targetApp.getId();
    }

    private void copyGeneratedCodeDir(App sourceApp, App targetApp) {
        String codeGenType = sourceApp.getCodeGenType();
        String sourceDirName = codeGenType + "_" + sourceApp.getId();
        String targetDirName = codeGenType + "_" + targetApp.getId();
        File sourceDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            log.warn("复制应用时源代码目录不存在，sourceAppId: {}, sourceDir: {}", sourceApp.getId(), sourceDir.getAbsolutePath());
            return;
        }
        File targetDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + targetDirName);
        try {
            FileUtil.copyContent(sourceDir, targetDir, true);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "复制应用代码失败：" + e.getMessage());
        }
    }

    private App getOwnedApp(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用代码");
        }
        return app;
    }

    private void startGenerationTask(Long appId,
                                     User loginUser,
                                     GenerationPreparation preparation,
                                     GenerationSession session) {
        Thread.startVirtualThread(() -> {
            StringBuilder generatedContent = new StringBuilder();
            long[] lastSnapshotUpdateAt = {0L};
            MonitorContextHolder.setContext(
                    MonitorContext.builder()
                            .userId(loginUser.getId().toString())
                            .appId(appId.toString())
                            .taskId(preparation.taskId())
                            .build()
            );
            try {
                preparation.events().forEach(session::emit);
                markGenerationStage(appId, preparation.generatingStage(), "智能体编排完成，正在生成项目代码...");
                runGenerationWithAutoRepair(appId, loginUser, preparation, session, generatedContent, lastSnapshotUpdateAt);
                if (session.isCancelled()) {
                    markGenerationFinished(appId);
                    session.emitStopped();
                    completeGenerationSession(session, preparation, "cancelled");
                    activeGenerationSessions.remove(appId, session);
                    generationToolExecutionContextService.clearContext(appId);
                    return;
                }
                if (preparation.requiresBuildValidation()) {
                    startBackgroundBuild(appId, loginUser, preparation, session);
                } else {
                    emitDiffSummaryIfAvailable(appId, preparation, session);
                    emitCommitResultIfAvailable(appId, preparation, session);
                    markGenerationFinished(appId);
                    completeGenerationSession(session, preparation, "success");
                    activeGenerationSessions.remove(appId, session);
                    generationToolExecutionContextService.clearContext(appId);
                }
            } catch (GenerationStoppedException e) {
                log.info("应用生成任务已停止，appId: {}", appId);
                markGenerationFinished(appId);
                session.emitStopped();
                completeGenerationSession(session, preparation, "cancelled");
                activeGenerationSessions.remove(appId, session);
                generationToolExecutionContextService.clearContext(appId);
            } catch (Exception e) {
                log.error("应用生成任务执行失败，appId: {}", appId, e);
                GenerationErrorClassifier.GenerationError generationError = classifyGenerationError(e);
                emitRollbackRestoreIfAllowed(appId, preparation, session);
                rollbackCodeGenTypeIfNeeded(appId, preparation);
                markGenerationFinished(appId);
                session.emit(GenerationStreamEvent.generationError(
                        generationError.message(),
                        buildGenerationErrorData(preparation, generationError)
                ));
                completeGenerationSession(session, preparation, "failed");
                activeGenerationSessions.remove(appId, session);
                generationToolExecutionContextService.clearContext(appId);
            } finally {
                MonitorContextHolder.clearContext();
            }
        });
    }

    private void startBackgroundBuild(Long appId,
                                      User loginUser,
                                      GenerationPreparation preparation,
                                      GenerationSession session) {
        markGenerationStage(appId, AppConstant.GENERATING_STAGE_BUILD, "代码已生成，正在后台构建校验...");
        Thread.startVirtualThread(() -> {
            MonitorContextHolder.setContext(
                    MonitorContext.builder()
                            .userId(loginUser.getId().toString())
                            .appId(appId.toString())
                            .taskId(preparation.taskId())
                            .build()
            );
            String completionStatus = "success";
            try {
                boolean buildSucceeded = runBackgroundBuildWithAutoRepair(appId, loginUser, preparation, session);
                if (buildSucceeded) {
                    emitDiffSummaryIfAvailable(appId, preparation, session);
                    emitCommitResultIfAvailable(appId, preparation, session);
                } else {
                    completionStatus = "failed";
                }
            } catch (Exception e) {
                completionStatus = "failed";
                log.error("后台构建校验失败，appId: {}", appId, e);
                GenerationErrorClassifier.GenerationError generationError = classifyGenerationError(e);
                emitRollbackRestoreIfAllowed(appId, preparation, session);
                rollbackCodeGenTypeIfNeeded(appId, preparation);
                session.emit(GenerationStreamEvent.generationError(
                        generationError.message(),
                        buildGenerationErrorData(preparation, generationError)
                ));
            } finally {
                markGenerationFinished(appId);
                completeGenerationSession(session, preparation, session.isCancelled() ? "cancelled" : completionStatus);
                activeGenerationSessions.remove(appId, session);
                generationToolExecutionContextService.clearContext(appId);
                MonitorContextHolder.clearContext();
            }
        });
    }

    private boolean runBackgroundBuildWithAutoRepair(Long appId,
                                                     User loginUser,
                                                     GenerationPreparation preparation,
                                                     GenerationSession session) {
        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator
                + preparation.targetType().getValue() + "_" + appId
                + (preparation.targetType() == CodeGenTypeEnum.FULL_STACK_PROJECT ? File.separator + "frontend" : "");
        StringBuilder generatedContent = new StringBuilder();
        long[] lastSnapshotUpdateAt = {0L};
        GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState =
                GeneratedProjectWorkspaceInspector.inspectVueProject(projectPath);
        if (!workspaceState.canAutoRepair()) {
            emitRollbackRestoreIfAllowed(appId, preparation, session);
            emitMissingProjectCodeError(appId, preparation, session, workspaceState);
            rollbackCodeGenTypeIfNeeded(appId, preparation);
            return false;
        }
        VueProjectBuilder.BuildResult buildResult = vueProjectBuilder.buildProjectWithResult(projectPath);
        if (session.isCancelled()) {
            return false;
        }
        session.emit(GenerationStreamEvent.buildResult(buildResult.toDiagnosticReport(), Map.of(
                "success", buildResult.success(),
                "stage", buildResult.stage(),
                "projectPath", buildResult.projectPath(),
                "summary", buildResult.summary(),
                "report", buildResult.toDiagnosticReport(),
                "taskId", preparation.taskId(),
                "qualityGate", preparation.qualityGateLevel(),
                "willAutoRepair", !buildResult.success() && workspaceState.canAutoRepair() && MAX_AUTO_REPAIR_ROUNDS > 0
        )));
        if (buildResult.success()) {
            return true;
        }
        if (MAX_AUTO_REPAIR_ROUNDS <= 0 || !workspaceState.canAutoRepair()) {
            emitRollbackRestoreIfAllowed(appId, preparation, session);
            rollbackCodeGenTypeIfNeeded(appId, preparation);
            GenerationErrorClassifier.GenerationError generationError =
                    classifyGenerationError(buildResult.toFailureSummary());
            session.emit(GenerationStreamEvent.generationError(
                    buildResult.toFailureSummary(),
                    buildGenerationErrorData(preparation, generationError, buildResult.toFailureSummary())
            ));
            return false;
        }
        for (int round = 1; round <= MAX_AUTO_REPAIR_ROUNDS; round++) {
            session.throwIfCancelled();
            workspaceState = GeneratedProjectWorkspaceInspector.inspectVueProject(projectPath);
            if (!workspaceState.canAutoRepair()) {
                emitRollbackRestoreIfAllowed(appId, preparation, session);
                emitMissingProjectCodeError(appId, preparation, session, workspaceState);
                rollbackCodeGenTypeIfNeeded(appId, preparation);
                return false;
            }
            markGenerationStage(appId, AppConstant.GENERATING_STAGE_REPAIR, "构建未通过，正在自动修复...");
            generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "build", "started");
            session.emit(GenerationStreamEvent.repairStart("\n\n[自动修复] 第 " + round + " 轮修复开始\n\n", Map.of(
                    "round", round,
                    "maxRounds", MAX_AUTO_REPAIR_ROUNDS,
                    "taskId", preparation.taskId(),
                    "agent", "BuildFix"
            )));
            try {
                executeGenerationRound(
                        appId,
                        loginUser,
                        preparation.targetType(),
                        buildAutoRepairPrompt(new BusinessException(ErrorCode.SYSTEM_ERROR, buildResult.toFailureSummary()), round),
                        session,
                        generatedContent,
                        lastSnapshotUpdateAt
                );
            } catch (Exception e) {
                generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "build", "failed");
                throw e;
            }
            markGenerationStage(appId, AppConstant.GENERATING_STAGE_BUILD, "自动修复完成，正在重新构建校验...");
            buildResult = vueProjectBuilder.buildProjectWithResult(projectPath);
            if (session.isCancelled()) {
                return false;
            }
            session.emit(GenerationStreamEvent.buildResult(buildResult.toDiagnosticReport(), Map.of(
                    "success", buildResult.success(),
                    "stage", buildResult.stage(),
                    "projectPath", buildResult.projectPath(),
                    "summary", buildResult.summary(),
                    "report", buildResult.toDiagnosticReport(),
                    "taskId", preparation.taskId(),
                    "qualityGate", preparation.qualityGateLevel()
            )));
            if (buildResult.success()) {
                generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "build", "success");
                return true;
            }
            generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "build", "failed");
        }
        emitRollbackRestoreIfAllowed(appId, preparation, session);
        rollbackCodeGenTypeIfNeeded(appId, preparation);
        GenerationErrorClassifier.GenerationError generationError =
                classifyGenerationError(buildResult.toFailureSummary());
        session.emit(GenerationStreamEvent.generationError(
                buildResult.toFailureSummary(),
                buildGenerationErrorData(preparation, generationError, buildResult.toFailureSummary())
        ));
        return false;
    }

    private void runGenerationWithAutoRepair(Long appId,
                                             User loginUser,
                                             GenerationPreparation preparation,
                                             GenerationSession session,
                                             StringBuilder generatedContent,
                                             long[] lastSnapshotUpdateAt) {
        String currentPrompt = preparation.enhancedMessage();
        Exception lastError = null;
        int maxGenerationRepairRounds = GenerationRepairPolicy.allowAutoRepair(
                preparation.generatingStage(),
                preparation.targetType(),
                MAX_AUTO_REPAIR_ROUNDS
        ) && preparation.requiresBuildValidation() ? MAX_AUTO_REPAIR_ROUNDS : 0;
        for (int round = 0; round <= maxGenerationRepairRounds; round++) {
            session.throwIfCancelled();
            if (round > 0) {
                generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "generation", "started");
                session.emit(GenerationStreamEvent.repairStart("\n\n[自动修复] 第 " + round + " 轮修复开始\n\n", Map.of(
                        "round", round,
                        "maxRounds", maxGenerationRepairRounds,
                        "taskId", preparation.taskId(),
                        "agent", "BuildFix"
                )));
            }
            try {
                executeGenerationRound(appId, loginUser, preparation.targetType(), currentPrompt, session, generatedContent, lastSnapshotUpdateAt);
                if (round > 0) {
                    generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "generation", "success");
                }
                return;
            } catch (Exception e) {
                lastError = e;
                GenerationErrorClassifier.GenerationError generationError = classifyGenerationError(e);
                log.warn("应用生成轮次失败，appId: {}, round: {}, category: {}, error: {}",
                        appId, round, generationError.category(), e.getMessage());
                if (round > 0) {
                    generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "generation", "failed");
                }
                if (e instanceof MissingGeneratedProjectException || !generationError.recoverable()) {
                    break;
                }
                if (round >= maxGenerationRepairRounds) {
                    break;
                }
                currentPrompt = buildAutoRepairPrompt(e, round + 1);
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                lastError == null ? "生成失败" : StrUtil.blankToDefault(lastError.getMessage(), "生成失败"));
    }

    private void executeGenerationRound(Long appId,
                                        User loginUser,
                                        CodeGenTypeEnum codeGenType,
                                        String prompt,
                                        GenerationSession session,
                                        StringBuilder generatedContent,
                                        long[] lastSnapshotUpdateAt) {
        Flux<GenerationStreamEvent> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(
                prompt, codeGenType, appId, session::isCancelled, session::setResponseHandle);
        streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, loginUser, codeGenType)
                .takeUntilOther(session.cancelSignal())
                .doOnNext(event -> {
                    session.throwIfCancelled();
                    appendGenerationSnapshotChunk(generatedContent, event.getText());
                    updateGenerationSnapshotIfDue(appId, generatedContent, lastSnapshotUpdateAt);
                    session.emit(event);
                })
                .doOnComplete(session::throwIfCancelled)
                .blockLast();
        verifyGeneratedProjectReady(appId, codeGenType);
    }

    private void emitDiffSummaryIfAvailable(Long appId,
                                            GenerationPreparation preparation,
                                            GenerationSession session) {
        if (session.isCancelled()) {
            return;
        }
        GenerationArtifact rollbackPoint = preparation.artifact("rollback_point");
        DiffSummary summary = generationDiffSummaryService.summarize(
                appId,
                preparation.targetType(),
                preparation.taskId(),
                rollbackPoint
        );
        GenerationArtifact diffSummary = GenerationArtifact.of(
                "diff_summary",
                "Orchestrator",
                "生成后差异摘要",
                summary.toPayload()
        );
        preparation.putArtifact(diffSummary);
        session.emit(GenerationStreamEvent.agentEvent(
                generationDiffSummaryService.renderText(summary),
                buildDiffSummaryEventData(preparation, diffSummary)
        ));
        emitPatchResultIfAvailable(appId, preparation, session, diffSummary);
    }

    private void emitPatchResultIfAvailable(Long appId,
                                            GenerationPreparation preparation,
                                            GenerationSession session,
                                            GenerationArtifact diffSummary) {
        if (session.isCancelled()) {
            return;
        }
        PatchResult patchResult = generationPatchResultService.evaluate(
                appId,
                preparation.taskId(),
                preparation.artifact("change_plan"),
                diffSummary
        );
        GenerationArtifact patchResultArtifact = GenerationArtifact.of(
                "patch_result",
                "Orchestrator",
                "Patch 实际落盘结果",
                patchResult.toPayload()
        );
        preparation.putArtifact(patchResultArtifact);
        generationOrchestrationMetricsCollector.recordPatchResult(
                "agent",
                patchResult.status(),
                patchResult.reason()
        );
        session.emit(GenerationStreamEvent.agentEvent(
                generationPatchResultService.renderText(patchResult),
                buildPatchResultEventData(preparation, patchResultArtifact)
        ));
        emitOrphanFileReviewIfAvailable(appId, preparation, session);
    }

    private void emitOrphanFileReviewIfAvailable(Long appId,
                                                 GenerationPreparation preparation,
                                                 GenerationSession session) {
        if (session.isCancelled()) {
            return;
        }
        Path projectRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, preparation.targetType().getValue() + "_" + appId);
        ChangePlan changePlan = preparation.artifact("change_plan") == null
                ? null
                : ChangePlan.fromPayload(preparation.artifact("change_plan").payload());
        OrphanFileReviewService.OrphanFileReviewResult result = orphanFileReviewService.review(projectRoot, changePlan);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.status());
        payload.put("orphanCandidates", result.orphanCandidates());
        payload.put("reasons", result.reasons());
        payload.put("deleteAllowedFiles", result.deleteAllowedFiles());
        payload.put("summary", result.summary());
        GenerationArtifact artifact = GenerationArtifact.of("orphan_file_review", "Orchestrator", "旧模板残留审查", payload);
        preparation.putArtifact(artifact);
        session.emit(GenerationStreamEvent.agentEvent(
                result.summary(),
                buildOrphanReviewEventData(preparation, artifact)
        ));
    }

    private void emitCommitResultIfAvailable(Long appId,
                                             GenerationPreparation preparation,
                                             GenerationSession session) {
        if (session.isCancelled()) {
            return;
        }
        GenerationCommitResult commitResult = generationCommitService.commit(
                appId,
                preparation.taskId(),
                preparation.artifact("diff_summary")
        );
        GenerationArtifact commitArtifact = GenerationArtifact.of(
                "generation_commit",
                "Orchestrator",
                "生成结果本地 Git 提交",
                commitResult.toPayload()
        );
        preparation.putArtifact(commitArtifact);
        generationOrchestrationMetricsCollector.recordGenerationCommit(
                commitResult.provider(),
                commitResult.status(),
                commitResult.reason()
        );
        session.emit(GenerationStreamEvent.agentEvent(
                generationCommitService.renderText(commitResult),
                buildCommitResultEventData(preparation, commitArtifact)
        ));
    }

    private Map<String, Object> buildDiffSummaryEventData(GenerationPreparation preparation,
                                                          GenerationArtifact diffSummary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "diff");
        data.put("status", diffSummary.payload().get("status"));
        data.put("summary", "created".equals(String.valueOf(diffSummary.payload().get("status")))
                ? "生成后差异摘要已生成"
                : "生成后差异摘要已跳过");
        data.put("taskId", preparation.taskId());
        data.put("artifact", diffSummary.payload());
        return data;
    }

    private Map<String, Object> buildPatchResultEventData(GenerationPreparation preparation,
                                                          GenerationArtifact patchResult) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "patch");
        data.put("status", patchResult.payload().get("status"));
        data.put("summary", "applied".equals(String.valueOf(patchResult.payload().get("status")))
                ? "Patch 实际落盘结果已对齐"
                : "Patch 实际落盘结果存在偏差或已跳过");
        data.put("taskId", preparation.taskId());
        data.put("artifact", patchResult.payload());
        return data;
    }

    private Map<String, Object> buildOrphanReviewEventData(GenerationPreparation preparation,
                                                           GenerationArtifact orphanReview) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "orphan_review");
        data.put("status", orphanReview.payload().get("status"));
        data.put("summary", orphanReview.payload().get("summary"));
        data.put("taskId", preparation.taskId());
        data.put("artifact", orphanReview.payload());
        return data;
    }

    private Map<String, Object> buildCommitResultEventData(GenerationPreparation preparation,
                                                           GenerationArtifact commitResult) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "commit");
        data.put("status", commitResult.payload().get("status"));
        data.put("summary", "committed".equals(String.valueOf(commitResult.payload().get("status")))
                ? "生成结果已提交到本地 Git"
                : "生成结果本地 Git 提交已跳过或失败");
        data.put("taskId", preparation.taskId());
        data.put("artifact", commitResult.payload());
        return data;
    }

    private void emitRollbackRestoreIfAllowed(Long appId,
                                              GenerationPreparation preparation,
                                              GenerationSession session) {
        if (session.isCancelled() || preparation.artifact("rollback_restore") != null) {
            return;
        }
        GenerationArtifact rollbackRestore = generationRollbackRestoreService.restoreIfAllowed(
                appId,
                preparation.taskId(),
                preparation.artifact("change_plan"),
                preparation.artifact("rollback_point")
        );
        preparation.putArtifact(rollbackRestore);
        Object status = rollbackRestore.payload().get("status");
        Object reason = rollbackRestore.payload().get("reason");
        generationOrchestrationMetricsCollector.recordRollbackRestore("agent", String.valueOf(status), String.valueOf(reason));
        session.emit(GenerationStreamEvent.agentEvent(
                buildRollbackRestoreMessage(rollbackRestore),
                buildRollbackRestoreEventData(preparation, rollbackRestore)
        ));
    }

    private String buildRollbackRestoreMessage(GenerationArtifact rollbackRestore) {
        Object status = rollbackRestore.payload().get("status");
        if ("restored".equals(String.valueOf(status))) {
            return "生成失败，已从本地回滚点恢复项目文件。";
        }
        if ("failed".equals(String.valueOf(status))) {
            return "生成失败，尝试从本地回滚点恢复项目文件未成功。";
        }
        return "生成失败，当前回滚策略未执行自动恢复。";
    }

    private Map<String, Object> buildRollbackRestoreEventData(GenerationPreparation preparation,
                                                              GenerationArtifact rollbackRestore) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "rollback");
        data.put("status", rollbackRestore.payload().get("status"));
        data.put("summary", buildRollbackRestoreMessage(rollbackRestore));
        data.put("taskId", preparation.taskId());
        data.put("artifact", rollbackRestore.payload());
        return data;
    }

    private void verifyGeneratedProjectReady(Long appId, CodeGenTypeEnum codeGenType) {
        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + codeGenType.getValue() + "_" + appId;
        if (codeGenType == CodeGenTypeEnum.BACKEND_PROJECT) {
            File projectDir = new File(projectPath);
            boolean ready = projectDir.isDirectory()
                    && new File(projectDir, "go.mod").isFile()
                    && new File(projectDir, "cmd/server/main.go").isFile();
            ThrowUtils.throwIf(!ready, ErrorCode.SYSTEM_ERROR, "生成结束但未发现有效后端工程，请重试生成");
            return;
        }
        if (codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            File projectDir = new File(projectPath);
            boolean ready = projectDir.isDirectory()
                    && new File(projectDir, "frontend/package.json").isFile()
                    && new File(projectDir, "backend/go.mod").isFile()
                    && new File(projectDir, "backend/cmd/server/main.go").isFile();
            ThrowUtils.throwIf(!ready, ErrorCode.SYSTEM_ERROR, "生成结束但未发现有效全栈工程，请重试生成");
            return;
        }
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT) {
            return;
        }
        GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState =
                GeneratedProjectWorkspaceInspector.inspectVueProject(projectPath);
        if (!workspaceState.canAutoRepair()) {
            throw new MissingGeneratedProjectException(workspaceState);
        }
    }

    private void emitMissingProjectCodeError(Long appId,
                                             GenerationPreparation preparation,
                                             GenerationSession session,
                                             GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState) {
        String message = buildMissingProjectCodeMessage(workspaceState);
        log.warn("生成结束但未发现有效项目代码，appId: {}, projectPath: {}, fileCount: {}, meaningfulFileCount: {}, keyFiles: {}",
                appId,
                workspaceState.rootPath(),
                workspaceState.fileCount(),
                workspaceState.meaningfulFileCount(),
                workspaceState.detectedKeyFiles());
        session.emit(GenerationStreamEvent.generationError(message, buildGenerationErrorData(
                preparation,
                "codegen_empty",
                message,
                true,
                Map.of(
                        "projectPath", workspaceState.rootPath().toString(),
                        "fileCount", workspaceState.fileCount(),
                        "meaningfulFileCount", workspaceState.meaningfulFileCount()
                )
        )));
    }

    private Map<String, Object> buildGenerationErrorData(GenerationPreparation preparation,
                                                         GenerationErrorClassifier.GenerationError generationError) {
        return buildGenerationErrorData(preparation, generationError, generationError.message());
    }

    private Map<String, Object> buildGenerationErrorData(GenerationPreparation preparation,
                                                         GenerationErrorClassifier.GenerationError generationError,
                                                         String message) {
        return buildGenerationErrorData(
                preparation,
                generationError.category(),
                message,
                generationError.recoverable(),
                Map.of()
        );
    }

    private Map<String, Object> buildGenerationErrorData(GenerationPreparation preparation,
                                                         String category,
                                                         String message,
                                                         boolean recoverable,
                                                         Map<String, Object> extraData) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", category);
        data.put("message", message);
        data.put("taskId", preparation.taskId());
        data.put("recoverable", recoverable);
        if (extraData != null) {
            data.putAll(extraData);
        }
        GenerationArtifact rollbackPoint = preparation.artifacts() == null ? null : preparation.artifacts().get("rollback_point");
        if (rollbackPoint != null) {
            data.put("rollback_point", rollbackPoint.payload());
        }
        GenerationArtifact diffSummary = preparation.artifacts() == null ? null : preparation.artifacts().get("diff_summary");
        if (diffSummary != null) {
            data.put("diff_summary", diffSummary.payload());
        }
        GenerationArtifact patchResult = preparation.artifacts() == null ? null : preparation.artifacts().get("patch_result");
        if (patchResult != null) {
            data.put("patch_result", patchResult.payload());
        }
        GenerationArtifact commitResult = preparation.artifacts() == null ? null : preparation.artifacts().get("generation_commit");
        if (commitResult != null) {
            data.put("generation_commit", commitResult.payload());
        }
        GenerationArtifact rollbackRestore = preparation.artifacts() == null ? null : preparation.artifacts().get("rollback_restore");
        if (rollbackRestore != null) {
            data.put("rollback_restore", rollbackRestore.payload());
        }
        return data;
    }

    private String buildMissingProjectCodeMessage(GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState) {
        return workspaceState.missingProjectSummary()
                + "。请重试生成；如果持续出现，请检查模型工具调用是否成功写入关键项目文件。";
    }

    private String buildAutoRepairPrompt(Exception exception, int repairRound) {
        String errorMessage = StrUtil.blankToDefault(exception.getMessage(), "构建失败");
        return """
                【自动修复任务】
                上一次 Vue 项目生成后未通过本地构建。请基于当前项目文件直接修复，不要重建整个项目。

                修复轮次：%d
                错误分类：%s
                错误摘要：
                %s

                必须遵守：
                1. 先使用项目搜索、目录读取或批量读取文件工具定位问题。
                2. 如果涉及依赖、scripts 或 package.json，先使用依赖问题分析工具，再用依赖与脚本管理工具处理。
                3. 只修改必要文件，避免无关重构。
                4. 修复后必须调用本地构建诊断工具验证。
                """.formatted(repairRound, classifyGenerationError(errorMessage).category(), errorMessage);
    }

    private GenerationErrorClassifier.GenerationError classifyGenerationError(Throwable throwable) {
        return GenerationErrorClassifier.classify(throwable);
    }

    private GenerationErrorClassifier.GenerationError classifyGenerationError(String errorMessage) {
        return GenerationErrorClassifier.classify(errorMessage);
    }

    private void resetResidualGenerationState(App app) {
        if (app == null) {
            return;
        }
        if (app.getIsGenerating() != null && app.getIsGenerating() == 1) {
            log.warn("检测到残留的生成状态，已自动重置，appId: {}", app.getId());
            markGenerationFinished(app.getId());
            app.setIsGenerating(0);
            app.setGeneratingMessage("");
        }
    }

    private Object getGenerationLock(Long appId) {
        return generationLocks.computeIfAbsent(appId, key -> new Object());
    }

    private GenerationPreparation prepareGeneration(App app, String userMessage) {
        GenerationIntent intent = recognizeGenerationIntent(app, userMessage);
        GenerationContextAssembly contextAssembly = assembleGenerationContext(intent);
        GenerationRoutingPlan routingPlan = routeGeneration(intent, contextAssembly);
        return buildGenerationPreparation(intent, routingPlan);
    }

    private GenerationIntent recognizeGenerationIntent(App app, String userMessage) {
        CodeGenTypeEnum currentType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(currentType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        return new GenerationIntent(
                app,
                currentType,
                appDatabaseResourceService.appendGenerationInstructionIfEnabled(app, userMessage),
                determineGeneratingStage(app),
                hasGeneratedCode(app)
        );
    }

    private GenerationContextAssembly assembleGenerationContext(GenerationIntent intent) {
        return new GenerationContextAssembly(
                createProjectContextSupplier(intent.app())
        );
    }

    private GenerationRoutingPlan routeGeneration(GenerationIntent intent, GenerationContextAssembly contextAssembly) {
        return new GenerationRoutingPlan(
                createRoutingFunction(intent.app(), intent.currentType()),
                contextAssembly
        );
    }

    private GenerationPreparation buildGenerationPreparation(GenerationIntent intent, GenerationRoutingPlan routingPlan) {
        GenerationOrchestrationResult orchestrationResult = generationOrchestrator.prepare(
                new GenerationOrchestrationRequest(
                        intent.app(),
                        intent.generationMessage(),
                        intent.currentType(),
                        intent.generatingStage(),
                        intent.hasGeneratedCode(),
                        routingPlan.contextAssembly().projectContextSupplier(),
                        routingPlan.routingFunction()
                )
        );
        GenerationPreparation preparation = new GenerationPreparation(
                orchestrationResult.originalType(),
                orchestrationResult.targetType(),
                orchestrationResult.upgradeRequired(),
                orchestrationResult.generatingStage(),
                orchestrationResult.enhancedMessage(),
                orchestrationResult.events(),
                orchestrationResult.artifacts(),
                orchestrationResult.qualityGateResult(),
                orchestrationResult.timings(),
                orchestrationResult.taskId()
        );
        bindToolExecutionContext(intent.app(), preparation);
        return preparation;
    }

    private void bindToolExecutionContext(App app, GenerationPreparation preparation) {
        if (app == null || app.getId() == null || preparation == null) {
            return;
        }
        GenerationArtifact changePlanArtifact = preparation.artifact("change_plan");
        ChangePlan changePlan = changePlanArtifact == null ? null : ChangePlan.fromPayload(changePlanArtifact.payload());
        boolean allowUnplannedWrite = changePlan != null && "project_bootstrap".equals(changePlan.changeScope());
        String generationMode = allowUnplannedWrite ? "full_generation" : "patch_first";
        generationToolExecutionContextService.bindChangePlan(
                app.getId(),
                preparation.taskId(),
                generationMode,
                preparation.targetType(),
                changePlan,
                allowUnplannedWrite,
                "orchestration_context"
        );
    }

    private CodeGenTypeEnum routeCodeGenTypeForPrompt(App app, String routingPrompt, CodeGenTypeEnum currentType) {
        try {
            AiCodeGenTypeRoutingService routingService = aiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService();
            CodeGenTypeEnum routedType = routingService.routeCodeGenType(routingPrompt);
            return routedType == null ? currentType : routedType;
        } catch (Exception e) {
            log.warn("生成前重新路由失败，沿用当前模式，appId: {}", app.getId(), e);
            return currentType;
        }
    }

    private Supplier<String> createProjectContextSupplier(App app) {
        return () -> buildProjectContext(app);
    }

    private Function<String, CodeGenTypeEnum> createRoutingFunction(App app, CodeGenTypeEnum currentType) {
        Map<String, CodeGenTypeEnum> routingCache = new ConcurrentHashMap<>();
        return routingPrompt -> routingCache.computeIfAbsent(
                routingPrompt,
                key -> routeCodeGenTypeForPrompt(app, key, currentType)
        );
    }

    private String determineGeneratingStage(App app) {
        if (app == null || app.getId() == null) {
            return AppConstant.GENERATING_STAGE_CREATE;
        }
        return hasGeneratedCode(app) ? AppConstant.GENERATING_STAGE_UPDATE : AppConstant.GENERATING_STAGE_CREATE;
    }

    private boolean hasGeneratedCode(App app) {
        try {
            getCodeRootDir(app);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    private void markGenerationStarted(Long appId, String generatingStage) {
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setIsGenerating(1);
        updateApp.setGeneratingMessage("");
        updateApp.setGeneratingStage(generatingStage);
        this.updateById(updateApp);
    }

    private void markGenerationStage(Long appId, String generatingStage, String generatingMessage) {
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setIsGenerating(1);
        updateApp.setGeneratingMessage(generatingMessage);
        updateApp.setGeneratingStage(generatingStage);
        this.updateById(updateApp);
    }

    private void updateGenerationPhase(Long appId, String generatingStage, String generatingMessage) {
        markGenerationStage(appId, generatingStage, generatingMessage);
    }

    private void updateGenerationSnapshot(Long appId, String generatingMessage) {
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setIsGenerating(1);
        updateApp.setGeneratingMessage(generatingMessage);
        this.updateById(updateApp);
    }

    private void updateGenerationSnapshotIfDue(Long appId, StringBuilder generatedContent, long[] lastSnapshotUpdateAt) {
        long now = System.currentTimeMillis();
        if (now - lastSnapshotUpdateAt[0] < GENERATION_SNAPSHOT_UPDATE_INTERVAL_MILLIS) {
            return;
        }
        lastSnapshotUpdateAt[0] = now;
        updateGenerationSnapshot(appId, generatedContent.toString());
    }

    private void appendGenerationSnapshotChunk(StringBuilder generatedContent, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        generatedContent.append(chunk);
        int overflowChars = generatedContent.length() - MAX_GENERATION_SNAPSHOT_CHARS;
        if (overflowChars > 0) {
            generatedContent.delete(0, overflowChars);
        }
    }

    private void completeGenerationSession(GenerationSession session,
                                           GenerationPreparation preparation,
                                           String status) {
        if (session == null || !session.tryMarkCompleted()) {
            return;
        }
        recordUserWaitMetric(session, preparation, status);
        session.complete();
    }

    private void recordUserWaitMetric(GenerationSession session,
                                      GenerationPreparation preparation,
                                      String status) {
        if (session == null || preparation == null) {
            return;
        }
        long orchestrationDurationMs = preparation.timings() == null
                ? 0L
                : preparation.timings().values().stream().mapToLong(Long::longValue).sum();
        generationOrchestrationMetricsCollector.recordUserWaitDuration(
                orchestrationMode(preparation),
                preparation.targetType() == null ? "unknown" : preparation.targetType().getValue(),
                status,
                Duration.between(session.startedAt(), Instant.now()).plusMillis(Math.max(0L, orchestrationDurationMs))
        );
    }

    private String orchestrationMode(GenerationPreparation preparation) {
        if (preparation == null || preparation.events() == null) {
            return "unknown";
        }
        return preparation.events().stream()
                .map(GenerationStreamEvent::getData)
                .filter(map -> map != null && map.get("orchestrationMode") != null)
                .map(map -> String.valueOf(map.get("orchestrationMode")))
                .findFirst()
                .orElse("unknown");
    }

    private void markGenerationFinished(Long appId) {
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setIsGenerating(0);
        updateApp.setGeneratingMessage("");
        updateApp.setGeneratingStage(null);
        this.updateById(updateApp);
    }

    private void switchAppCodeGenType(Long appId, CodeGenTypeEnum targetType) {
        if (targetType == null) {
            return;
        }
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setCodeGenType(targetType.getValue());
        this.updateById(updateApp);
    }

    private void rollbackCodeGenTypeIfNeeded(Long appId, GenerationPreparation preparation) {
        if (preparation == null || !preparation.upgradeRequired()) {
            return;
        }
        cleanupCodeDir(appId, preparation.targetType());
        switchAppCodeGenType(appId, preparation.originalType());
    }

    private void cleanupCodeDir(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        if (appId == null || appId <= 0 || codeGenTypeEnum == null) {
            return;
        }
        File codeDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + codeGenTypeEnum.getValue() + "_" + appId);
        if (!codeDir.exists()) {
            return;
        }
        try {
            File canonicalRoot = new File(AppConstant.CODE_OUTPUT_ROOT_DIR).getCanonicalFile();
            File canonicalDir = codeDir.getCanonicalFile();
            if (!canonicalDir.toPath().startsWith(canonicalRoot.toPath())) {
                log.warn("跳过清理非法代码目录，appId: {}, dir: {}", appId, canonicalDir.getAbsolutePath());
                return;
            }
            FileUtil.del(canonicalDir);
        } catch (Exception e) {
            log.warn("清理升级失败目录时发生异常，appId: {}, type: {}", appId, codeGenTypeEnum.getValue(), e);
        }
    }

    private File getCodeRootDir(App app) {
        String sourceDirName = app.getCodeGenType() + "_" + app.getId();
        File rootDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName);
        ThrowUtils.throwIf(!rootDir.exists() || !rootDir.isDirectory(),
                ErrorCode.NOT_FOUND_ERROR, "应用代码不存在，请先生成代码");
        return rootDir;
    }

    private File resolveCodeFile(File rootDir, String filePath) {
        ThrowUtils.throwIf(StrUtil.isBlank(filePath), ErrorCode.PARAMS_ERROR, "文件路径不能为空");
        try {
            String normalizedInputPath = filePath.replace("\\", "/");
            for (String pathPart : normalizedInputPath.split("/")) {
                ThrowUtils.throwIf(HIDDEN_FILE_NAMES.contains(pathPart), ErrorCode.NO_AUTH_ERROR, "禁止访问该文件");
            }
            Path rootPath = rootDir.getCanonicalFile().toPath();
            Path targetPath = rootPath.resolve(normalizedInputPath.replace("/", File.separator)).normalize();
            ThrowUtils.throwIf(!targetPath.startsWith(rootPath), ErrorCode.NO_AUTH_ERROR, "非法文件路径");
            return targetPath.toFile();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件路径解析失败");
        }
    }

    private AppCodeFileTreeVO buildFileTreeNode(File rootDir, File file, int depth) {
        AppCodeFileTreeVO node = new AppCodeFileTreeVO();
        node.setName(file.getName());
        node.setPath(normalizeRelativePath(rootDir, file));
        node.setDirectory(file.isDirectory());
        node.setSize(file.isFile() ? file.length() : 0L);
        if (file.isDirectory() && depth < MAX_FILE_TREE_DEPTH) {
            File[] children = file.listFiles(child -> !shouldHideFile(child));
            if (children != null) {
                List<AppCodeFileTreeVO> childNodes = Arrays.stream(children)
                        .sorted(fileComparator())
                        .map(child -> buildFileTreeNode(rootDir, child, depth + 1))
                        .collect(Collectors.toList());
                node.setChildren(childNodes);
            }
        }
        return node;
    }

    private Comparator<File> fileComparator() {
        return Comparator
                .comparing(File::isFile)
                .thenComparing(file -> file.getName().toLowerCase());
    }

    private String normalizeRelativePath(File rootDir, File file) {
        try {
            Path rootPath = rootDir.getCanonicalFile().toPath();
            Path filePath = file.getCanonicalFile().toPath();
            return rootPath.relativize(filePath).toString().replace(File.separator, "/");
        } catch (Exception e) {
            return file.getName();
        }
    }

    private boolean shouldHideFile(File file) {
        return HIDDEN_FILE_NAMES.contains(file.getName());
    }

    private boolean isEditableFile(File file) {
        String fileName = file.getName();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        String extension = fileName.substring(dotIndex + 1).toLowerCase();
        return EDITABLE_EXTENSIONS.contains(extension);
    }

    private String buildEnhancedUserMessage(App app,
                                            String userMessage,
                                            CodeGenTypeEnum currentType,
                                            CodeGenTypeEnum targetType) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append(userMessage);
        if (currentType != null && targetType != null && currentType.canUpgradeTo(targetType)) {
            promptBuilder.append("\n\n")
                    .append("【模式升级要求】\n")
                    .append("当前应用原本使用 ")
                    .append(currentType.getText())
                    .append("，但这次需求复杂度已经升级，请将项目整体升级为 ")
                    .append(targetType.getText())
                    .append("。\n")
                    .append("必须保留并迁移已有页面能力、样式意图和业务内容，输出结果要以新模式可继续迭代的工程结构为准。");
        }
        String projectContext = buildProjectContext(app);
        if (StrUtil.isNotBlank(projectContext)) {
            promptBuilder.append("\n\n")
                    .append(AppConstant.PROJECT_CONTEXT_MARKER)
                    .append("\n")
                    .append("这是当前应用已生成的代码摘要。后续回答必须基于这些现有内容继续修改、恢复或说明，不能声称无法访问当前项目或无法还原上一版内容。\n\n")
                    .append(projectContext);
        }
        return promptBuilder.toString();
    }

    private String buildProjectContext(App app) {
        try {
            File rootDir = getCodeRootDir(app);
            CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
            if (codeGenTypeEnum == null) {
                return "";
            }
            String projectIndex = buildProjectIndex(rootDir);
            String keyFiles = switch (codeGenTypeEnum) {
                case HTML -> readSingleFileContext(rootDir, "index.html");
                case MULTI_FILE -> readMultiFileContext(rootDir, List.of("index.html", "style.css", "script.js"));
                case VUE_PROJECT -> readMultiFileContext(rootDir, List.of("src/App.vue", "src/main.js", "src/main.ts", "index.html"));
                case BACKEND_PROJECT -> readMultiFileContext(rootDir, List.of("go.mod", "cmd/server/main.go", "internal/config/config.go", "internal/database/database.go", "sql/schema.sql"));
                case FULL_STACK_PROJECT -> readMultiFileContext(rootDir, List.of("frontend/package.json", "frontend/src/services/request.ts", "frontend/src/App.vue", "backend/go.mod", "backend/cmd/server/main.go", "backend/internal/config/config.go", "backend/sql/schema.sql", ".env.example"));
            };
            if (StrUtil.isBlank(projectIndex)) {
                return keyFiles;
            }
            if (StrUtil.isBlank(keyFiles)) {
                return projectIndex;
            }
            return projectIndex + "\n\n" + keyFiles;
        } catch (Exception e) {
            log.warn("构建项目上下文失败，appId: {}, error: {}", app.getId(), e.getMessage());
            return "";
        }
    }

    private String buildProjectIndex(File rootDir) {
        List<String> indexedFiles = new ArrayList<>();
        try {
            FileUtil.walkFiles(rootDir, file -> {
                if (indexedFiles.size() >= MAX_PROJECT_INDEX_FILES) {
                    return;
                }
                if (shouldHideFile(file)) {
                    return;
                }
                String relativePath = normalizeRelativePath(rootDir, file);
                if (file.isDirectory()) {
                    return;
                }
                String extension = FileUtil.extName(file).toLowerCase();
                if (isIndexableProjectFile(relativePath, extension)) {
                    indexedFiles.add(relativePath);
                }
            });
        } catch (Exception e) {
            log.warn("构建项目索引失败: {}", e.getMessage());
        }
        if (indexedFiles.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("项目索引:\n");
        indexedFiles.stream()
                .sorted()
                .limit(MAX_PROJECT_INDEX_FILES)
                .forEach(path -> builder.append("- ").append(path).append('\n'));
        return builder.toString().trim();
    }

    private boolean isIndexableProjectFile(String relativePath, String extension) {
        if (StrUtil.isBlank(relativePath)) {
            return false;
        }
        if (relativePath.startsWith("src/") || relativePath.startsWith("public/") || relativePath.startsWith("cmd/") || relativePath.startsWith("internal/") || relativePath.startsWith("sql/") || relativePath.startsWith("frontend/") || relativePath.startsWith("backend/")) {
            return Set.of("vue", "js", "ts", "jsx", "tsx", "css", "scss", "less", "json", "svg", "md", "go", "sql", "mod", "sum", "yml", "yaml").contains(extension);
        }
        return Set.of("package.json", "vite.config.js", "vite.config.ts", "index.html", "tsconfig.json", "tsconfig.app.json", "go.mod", "go.sum", "README.md", "docker-compose.yml", ".env.example")
                .contains(relativePath);
    }

    private String readSingleFileContext(File rootDir, String relativePath) {
        File file = new File(rootDir, relativePath);
        if (!file.exists() || !file.isFile()) {
            return "";
        }
        String content = FileUtil.readString(file, StandardCharsets.UTF_8);
        return "当前文件: " + relativePath + "\n```html\n" + truncateForModel(content) + "\n```";
    }

    private String readMultiFileContext(File rootDir, List<String> relativePaths) {
        List<String> sections = new ArrayList<>();
        for (String relativePath : relativePaths) {
            File file = new File(rootDir, relativePath);
            if (!file.exists() || !file.isFile()) {
                continue;
            }
            String extension = FileUtil.extName(file);
            String content = FileUtil.readString(file, StandardCharsets.UTF_8);
            sections.add("当前文件: " + relativePath + "\n```" + extension + "\n" + truncateForModel(content) + "\n```");
        }
        return String.join("\n\n", sections);
    }

    private String truncateForModel(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= MAX_MODEL_CONTEXT_FILE_CHARS) {
            return content;
        }
        return content.substring(0, MAX_MODEL_CONTEXT_FILE_CHARS)
                + "\n<!-- 文件内容过长，以上为截断后的前 "
                + MAX_MODEL_CONTEXT_FILE_CHARS
                + " 个字符 -->";
    }

    private record GenerationIntent(App app,
                                    CodeGenTypeEnum currentType,
                                    String generationMessage,
                                    String generatingStage,
                                    boolean hasGeneratedCode) {
    }

    private record GenerationContextAssembly(Supplier<String> projectContextSupplier) {
    }

    private record GenerationRoutingPlan(Function<String, CodeGenTypeEnum> routingFunction,
                                         GenerationContextAssembly contextAssembly) {
    }

    private record GenerationPreparation(CodeGenTypeEnum originalType,
                                         CodeGenTypeEnum targetType,
                                         boolean upgradeRequired,
                                         String generatingStage,
                                         String enhancedMessage,
                                         List<GenerationStreamEvent> events,
                                         Map<String, GenerationArtifact> artifacts,
                                         QualityGateResult qualityGateResult,
                                         Map<String, Long> timings,
                                         String taskId) {

        private String qualityGateLevel() {
            return qualityGateResult == null ? "unknown" : qualityGateResult.level();
        }

        private boolean requiresBuildValidation() {
            if (targetType != CodeGenTypeEnum.VUE_PROJECT && targetType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
                return false;
            }
            GenerationArtifact generationSpec = artifacts == null ? null : artifacts.get("generation_spec");
            if (generationSpec == null || generationSpec.payload() == null) {
                return true;
            }
            Object requiresBuild = generationSpec.payload().get("requiresBuild");
            return requiresBuild == null || Boolean.TRUE.equals(requiresBuild);
        }

        private GenerationArtifact artifact(String key) {
            return artifacts == null ? null : artifacts.get(key);
        }

        private void putArtifact(GenerationArtifact artifact) {
            if (artifacts == null || artifact == null) {
                return;
            }
            artifacts.put(artifact.key(), artifact);
        }
    }

    private final class MissingGeneratedProjectException extends BusinessException {

        private MissingGeneratedProjectException(GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState) {
            super(ErrorCode.SYSTEM_ERROR, buildMissingProjectCodeMessage(workspaceState));
        }
    }

    private void rebuildIfVueProject(App app, File rootDir) {
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenTypeEnum != CodeGenTypeEnum.VUE_PROJECT && codeGenTypeEnum != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return;
        }
        File buildRoot = codeGenTypeEnum == CodeGenTypeEnum.FULL_STACK_PROJECT ? new File(rootDir, "frontend") : rootDir;
        VueProjectBuilder.BuildResult buildResult = vueProjectBuilder.buildProjectWithResult(buildRoot.getAbsolutePath());
        ThrowUtils.throwIf(!buildResult.success(), ErrorCode.SYSTEM_ERROR, buildResult.toFailureSummary());
    }

    private void rollbackSavedFile(App app, File rootDir, File targetFile, String originalContent) {
        try {
            FileUtil.writeString(originalContent, targetFile, StandardCharsets.UTF_8);
            rebuildIfVueProject(app, rootDir);
        } catch (Exception e) {
            log.error("保存失败后回滚文件异常，appId: {}, file: {}", app.getId(), targetFile.getAbsolutePath(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存失败且自动回退异常，请联系管理员处理");
        }
    }

    /**
     * 异步生成应用截图并更新封面
     *
     * @param appId  应用ID
     * @param appUrl 应用访问URL
     */
    @Override
    public void generateAppScreenshotAsync(Long appId, String appUrl) {
        // 使用虚拟线程并执行
        Thread.startVirtualThread(() -> {
            // 调用截图服务生成截图并上传
            String screenshotUrl = screenshotService.generateAndUploadScreenshot(appUrl);
            // 更新数据库的封面
            App updateApp = new App();
            updateApp.setId(appId);
            updateApp.setCover(screenshotUrl);
            boolean updated = this.updateById(updateApp);
            ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "更新应用封面字段失败");
        });
    }

    private static final class GenerationSession {

        private final Sinks.Many<GenerationStreamEvent> sink = Sinks.many().replay().limit(MAX_GENERATION_REPLAY_EVENTS);
        private final Sinks.Empty<Void> cancelSink = Sinks.empty();
        private final GenerationPreparation preparation;
        private final Instant startedAt = Instant.now();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final AtomicReference<dev.langchain4j.model.openai.internal.ResponseHandle> responseHandleRef = new AtomicReference<>();

        private GenerationSession(GenerationPreparation preparation) {
            this.preparation = preparation;
        }

        private Instant startedAt() {
            return startedAt;
        }

        private GenerationPreparation preparation() {
            return preparation;
        }

        private boolean tryMarkCompleted() {
            return completed.compareAndSet(false, true);
        }

        private Flux<GenerationStreamEvent> asFlux() {
            return sink.asFlux();
        }

        private void emit(GenerationStreamEvent event) {
            if (completed.get()) {
                return;
            }
            sink.tryEmitNext(event);
        }

        private void complete() {
            sink.tryEmitComplete();
        }

        private void error(Throwable throwable) {
            if (!tryMarkCompleted()) {
                return;
            }
            sink.tryEmitError(throwable);
        }

        private void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                dev.langchain4j.model.openai.internal.ResponseHandle handle = responseHandleRef.get();
                if (handle != null) {
                    handle.cancel();
                }
                cancelSink.tryEmitEmpty();
            }
        }

        private boolean isCancelled() {
            return cancelled.get();
        }

        private boolean isActive() {
            return !completed.get() && !cancelled.get();
        }

        private Flux<Void> cancelSignal() {
            return cancelSink.asMono().flux();
        }

        private void setResponseHandle(dev.langchain4j.model.openai.internal.ResponseHandle responseHandle) {
            responseHandleRef.set(responseHandle);
            if (cancelled.get() && responseHandle != null) {
                responseHandle.cancel();
            }
        }

        private void throwIfCancelled() {
            if (isCancelled()) {
                throw new GenerationStoppedException();
            }
        }

        private void emitStopped() {
            emit(GenerationStreamEvent.generationStopped("\n\n[系统] 已停止本次生成\n\n", Map.of(
                    "message", "已停止本次生成"
            )));
        }
    }

    private static final class GenerationStoppedException extends RuntimeException {
    }

    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        appVO.setDatabaseResource(appDatabaseResourceService.getResourceVO(appDatabaseResourceService.getByAppId(app.getId())));
        // 关联查询用户信息
        Long userId = app.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            appVO.setUser(userVO);
        }
        return appVO;
    }

    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 批量获取用户信息，避免 N+1 查询问题
        Set<Long> userIds = appList.stream()
                .map(App::getUserId)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO));
        return appList.stream().map(app -> {
            AppVO appVO = getAppVO(app);
            UserVO userVO = userVOMap.get(app.getUserId());
            appVO.setUser(userVO);
            return appVO;
        }).collect(Collectors.toList());
    }

    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        return QueryWrapper.create()
                .eq("id", id)
                .like("appName", appName)
                .like("cover", cover)
                .like("initPrompt", initPrompt)
                .eq("codeGenType", codeGenType)
                .eq("deployKey", deployKey)
                .eq("priority", priority)
                .eq("userId", userId)
                .orderBy(sortField, "ascend".equals(sortOrder));    }

    /**
     * 删除应用时，关联删除对话历史
     *
     * @param id
     * @return
     */
    @Override
    public boolean removeById(Serializable id) {
        if (id == null) {
            return false;
        }
        long appId = Long.parseLong(id.toString());
        if (appId <= 0) {
            return false;
        }
        // 先删除关联的对话历史
        try {
            chatHistoryService.deleteByAppId(appId);
        } catch (Exception e) {
            log.error("删除应用关联的对话历史失败：{}", e.getMessage());
        }
        // 删除应用
        return super.removeById(id);
    }
}
