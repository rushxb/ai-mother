package com.rush.rushaicodemother.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingService;
import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingServiceFactory;
import com.rush.rushaicodemother.ai.AppNameGeneratorService;
import com.rush.rushaicodemother.ai.PromptOptimizerService;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.dto.app.AppAddRequest;
import com.rush.rushaicodemother.model.dto.app.AppCodeFileSaveRequest;
import com.rush.rushaicodemother.model.dto.app.AppQueryRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.vo.AppCodeFileContentVO;
import com.rush.rushaicodemother.model.vo.AppCodeFileTreeVO;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;
import com.rush.rushaicodemother.model.vo.AppVO;
import com.rush.rushaicodemother.model.vo.UserVO;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationTaskOrchestrator;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.DevServerManager;
import com.rush.rushaicodemother.service.ScreenshotService;
import com.rush.rushaicodemother.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private ChatHistoryService chatHistoryService;

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
    private GenerationTaskOrchestrator generationTaskOrchestrator;

    @Resource
    private AppDatabaseResourceService appDatabaseResourceService;

    @Resource
    private DevServerManager devServerManager;

    @Override
    public Flux<GenerationStreamEvent> chatToGenCode(Long appId, String message, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        App app = getGenerationApp(appId, loginUser);
        userService.ensureHasCredit(loginUser.getId());
        enableDatabaseForGenerationIfNeeded(app, message);
        GenerationTaskResult taskResult = generationTaskOrchestrator.start(new GenerationTaskRequest(app, message, loginUser));
        return taskResult.contentFlux();
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

    @Override
    public Flux<GenerationStreamEvent> getGenerationStream(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        return generationTaskOrchestrator.getStream(app.getId());
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

        // 为 Vue 项目预分配 dev server 端口
        if (selectedCodeGenType == CodeGenTypeEnum.VUE_PROJECT || selectedCodeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            int port = devServerManager.allocatePort(app.getId());
            app.setDevServerPort(port);
            log.info("为 Vue 项目 {} 预分配端口 {}", app.getId(), port);
        }

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

    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }

        // 为已存在的 Vue 项目懒加载分配端口（必须在 BeanUtil.copyProperties 之前）
        lazilyAllocateDevServerPort(app);

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

    /**
     * 为已存在的 Vue 项目懒加载分配端口
     * 如果 Vue 项目没有分配端口，则自动分配并更新数据库
     */
    private void lazilyAllocateDevServerPort(App app) {
        if (app.getDevServerPort() != null) {
            return;
        }
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT && codeGenType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return;
        }
        try {
            int port = devServerManager.allocatePort(app.getId());
            App updateApp = new App();
            updateApp.setId(app.getId());
            updateApp.setDevServerPort(port);
            this.updateById(updateApp);
            app.setDevServerPort(port);
            log.info("为已存在的 Vue 项目 {} 懒加载分配端口 {}", app.getId(), port);
        } catch (Exception e) {
            log.warn("为 Vue 项目 {} 分配端口失败: {}", app.getId(), e.getMessage());
        }
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

