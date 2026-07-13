package com.rush.rushaicodemother.service.deployment;

import com.rush.rushaicodemother.config.CodeDeploymentProperties;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.ScreenshotService;
import com.rush.rushaicodemother.service.artifact.AppArtifactLifecycleService;
import com.rush.rushaicodemother.service.artifact.DeploymentArtifactTransaction;
import com.rush.rushaicodemother.service.lifecycle.AppOperationLockManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** 本地静态应用部署实现。 */
@Service
@Slf4j
public class LocalAppDeploymentService implements AppDeploymentService {

    private static final int DEPLOY_KEY_LENGTH = 12;
    private static final String DEPLOY_KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final Pattern DEPLOY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9]{6,64}");

    private final AppArtifactLifecycleService artifactLifecycleService;
    private final VueProjectBuilder vueProjectBuilder;
    private final ScreenshotService screenshotService;
    private final AppMapper appMapper;
    private final CodeDeploymentProperties deploymentProperties;
    private final Supplier<String> deployKeySupplier;
    private final Executor screenshotExecutor;
    private final AppOperationLockManager operationLockManager;
    private final ConcurrentMap<Long, String> screenshotVersions;

    @Autowired
    public LocalAppDeploymentService(AppArtifactLifecycleService artifactLifecycleService,
                                     VueProjectBuilder vueProjectBuilder,
                                     ScreenshotService screenshotService,
                                     AppMapper appMapper,
                                     CodeDeploymentProperties deploymentProperties,
                                     AppOperationLockManager operationLockManager) {
        this(
                artifactLifecycleService,
                vueProjectBuilder,
                screenshotService,
                appMapper,
                deploymentProperties,
                operationLockManager,
                secureDeployKeySupplier(),
                task -> Thread.ofVirtual().name("app-deployment-screenshot").start(task)
        );
    }

    LocalAppDeploymentService(AppArtifactLifecycleService artifactLifecycleService,
                              VueProjectBuilder vueProjectBuilder,
                              ScreenshotService screenshotService,
                              AppMapper appMapper,
                              CodeDeploymentProperties deploymentProperties,
                              AppOperationLockManager operationLockManager,
                              Supplier<String> deployKeySupplier,
                              Executor screenshotExecutor) {
        this.artifactLifecycleService = artifactLifecycleService;
        this.vueProjectBuilder = vueProjectBuilder;
        this.screenshotService = screenshotService;
        this.appMapper = appMapper;
        this.deploymentProperties = deploymentProperties;
        this.deployKeySupplier = deployKeySupplier;
        this.screenshotExecutor = screenshotExecutor;
        this.operationLockManager = operationLockManager;
        this.screenshotVersions = new ConcurrentHashMap<>();
    }

    @Override
    public String deploy(App app) {
        return deployWithCurrentState(app, false);
    }

    @Override
    public String synchronize(App app) {
        return deployWithCurrentState(app, true);
    }

    private String deployWithCurrentState(App requestedApp, boolean requireExistingDeployment) {
        validateApp(requestedApp);
        requireSupportedCodeType(requestedApp.getCodeGenType());
        return operationLockManager.execute(requestedApp.getId(), () -> {
            App currentApp = appMapper.selectDeploymentState(requestedApp.getId());
            ThrowUtils.throwIf(currentApp == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
            if (requireExistingDeployment) {
                ThrowUtils.throwIf(currentApp.getDeployKey() == null || currentApp.getDeployKey().isBlank(),
                        ErrorCode.OPERATION_ERROR, "应用尚未部署，请先部署后再同步");
            }
            String deployUrl = deployLocked(currentApp);
            requestedApp.setDeployKey(currentApp.getDeployKey());
            requestedApp.setDeployedTime(currentApp.getDeployedTime());
            return deployUrl;
        });
    }

    private String deployLocked(App app) {
        CodeGenTypeEnum codeGenType = requireSupportedCodeType(app.getCodeGenType());
        Path generatedDirectory = artifactLifecycleService.requireGeneratedDirectory(app);
        Path deployableDirectory = prepareDeployableDirectory(codeGenType, generatedDirectory);
        String deployKey = resolveDeployKey(app.getDeployKey());
        LocalDateTime deployedTime = LocalDateTime.now().withNano(0);
        DeploymentArtifactTransaction artifactTransaction =
                artifactLifecycleService.prepareDeployment(deployableDirectory, deployKey);

        try {
            artifactTransaction.activate();
            int updatedRows = appMapper.updateDeploymentMetadata(app.getId(), deployKey, deployedTime);
            ThrowUtils.throwIf(updatedRows != 1, ErrorCode.OPERATION_ERROR, "更新应用部署信息失败");
            artifactTransaction.commit();
        } catch (RuntimeException deploymentFailure) {
            rollbackDeployment(artifactTransaction, deploymentFailure);
            throw deploymentFailure;
        }

        app.setDeployKey(deployKey);
        app.setDeployedTime(deployedTime);
        String deployUrl = buildDeployUrl(deployKey);
        String screenshotVersion = UUID.randomUUID().toString();
        screenshotVersions.put(app.getId(), screenshotVersion);
        generateScreenshotAsync(app.getId(), deployedTime, deployUrl, screenshotVersion);
        return deployUrl;
    }

    private Path prepareDeployableDirectory(CodeGenTypeEnum codeGenType, Path generatedDirectory) {
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT) {
            return generatedDirectory;
        }
        VueProjectBuilder.BuildResult buildResult =
                vueProjectBuilder.buildProjectWithResult(generatedDirectory.toString());
        ThrowUtils.throwIf(buildResult == null || !buildResult.success(),
                ErrorCode.SYSTEM_ERROR,
                buildResult == null ? "Vue 项目构建失败" : buildResult.toFailureSummary());
        Path distDirectory = generatedDirectory.resolve("dist").normalize();
        ThrowUtils.throwIf(!distDirectory.startsWith(generatedDirectory)
                        || !Files.isDirectory(distDirectory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(distDirectory),
                ErrorCode.SYSTEM_ERROR, "Vue 项目构建完成但未生成安全的 dist 目录");
        return distDirectory;
    }

    private CodeGenTypeEnum requireSupportedCodeType(String codeGenTypeValue) {
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(codeGenTypeValue);
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        if (codeGenType == CodeGenTypeEnum.BACKEND_PROJECT) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "后端工程暂不支持静态部署，请下载后本地运行或后续接入容器化部署");
        }
        if (codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "全栈工程暂不支持静态部署，请接入容器化部署后再发布");
        }
        return codeGenType;
    }

    private String resolveDeployKey(String existingDeployKey) {
        String deployKey = existingDeployKey == null || existingDeployKey.isBlank()
                ? deployKeySupplier.get()
                : existingDeployKey;
        ThrowUtils.throwIf(deployKey == null || !DEPLOY_KEY_PATTERN.matcher(deployKey).matches(),
                ErrorCode.PARAMS_ERROR, "部署标识格式错误");
        return deployKey;
    }

    private String buildDeployUrl(String deployKey) {
        String deployHost = deploymentProperties.getDeployHost().trim();
        while (deployHost.endsWith("/")) {
            deployHost = deployHost.substring(0, deployHost.length() - 1);
        }
        return deployHost + "/" + deployKey + "/";
    }

    private void generateScreenshotAsync(Long appId,
                                         LocalDateTime deployedTime,
                                         String deployUrl,
                                         String screenshotVersion) {
        try {
            screenshotExecutor.execute(() -> {
                try {
                    if (!screenshotVersion.equals(screenshotVersions.get(appId))) {
                        return;
                    }
                    String screenshotUrl = screenshotService.generateAndUploadScreenshot(deployUrl);
                    if (screenshotUrl == null || screenshotUrl.isBlank()) {
                        log.warn("应用部署截图生成结果为空，appId: {}, deployedTime: {}", appId, deployedTime);
                        return;
                    }
                    updateCoverIfDeploymentIsCurrent(appId, deployedTime, screenshotUrl, screenshotVersion);
                } catch (Exception exception) {
                    log.error("异步生成应用部署截图失败，appId: {}, url: {}", appId, deployUrl, exception);
                } finally {
                    screenshotVersions.remove(appId, screenshotVersion);
                }
            });
        } catch (RuntimeException exception) {
            screenshotVersions.remove(appId, screenshotVersion);
            log.error("提交应用部署截图任务失败，appId: {}, url: {}", appId, deployUrl, exception);
        }
    }

    private void updateCoverIfDeploymentIsCurrent(Long appId,
                                                  LocalDateTime deployedTime,
                                                  String screenshotUrl,
                                                  String screenshotVersion) {
        operationLockManager.execute(appId, () -> {
            if (!screenshotVersion.equals(screenshotVersions.get(appId))) {
                return;
            }
            int updatedRows = appMapper.updateCoverForDeployment(appId, deployedTime, screenshotUrl);
            if (updatedRows != 1) {
                log.info("忽略已过期的应用部署截图，appId: {}, deployedTime: {}", appId, deployedTime);
            }
        });
    }

    private void rollbackDeployment(DeploymentArtifactTransaction artifactTransaction,
                                    RuntimeException deploymentFailure) {
        try {
            artifactTransaction.rollback();
        } catch (RuntimeException rollbackFailure) {
            BusinessException consistencyFailure = new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "应用部署失败且部署目录回滚失败，请联系管理员处理",
                    deploymentFailure
            );
            consistencyFailure.addSuppressed(rollbackFailure);
            throw consistencyFailure;
        }
    }

    private void validateApp(App app) {
        ThrowUtils.throwIf(app == null || app.getId() == null || app.getId() <= 0,
                ErrorCode.PARAMS_ERROR, "应用参数错误");
    }

    private static Supplier<String> secureDeployKeySupplier() {
        SecureRandom secureRandom = new SecureRandom();
        return () -> {
            StringBuilder deployKey = new StringBuilder(DEPLOY_KEY_LENGTH);
            for (int index = 0; index < DEPLOY_KEY_LENGTH; index++) {
                deployKey.append(DEPLOY_KEY_ALPHABET.charAt(
                        secureRandom.nextInt(DEPLOY_KEY_ALPHABET.length())
                ));
            }
            return deployKey.toString();
        };
    }
}
