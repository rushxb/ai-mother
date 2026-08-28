package com.rush.rushaicodemother.application.app;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.dto.app.AppAddRequest;
import com.rush.rushaicodemother.model.dto.app.AppAdminUpdateRequest;
import com.rush.rushaicodemother.model.dto.app.AppUpdateRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.lifecycle.AppDeletionService;
import com.rush.rushaicodemother.service.provisioning.AppProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 应用生命周期管理模块。
 *
 * <p>集中处理创建、复制、更新、删除以及相关授权规则，保证不同控制入口共享同一业务语义。</p>
 */
@Service
@RequiredArgsConstructor
public class AppManagementApplicationService {

    private static final int MAX_APP_NAME_LENGTH = 50;

    private final AppPersistenceService appPersistenceService;
    private final AppAccessPolicy appAccessPolicy;
    private final AppProvisioningService appProvisioningService;
    private final AppDeletionService appDeletionService;

    public Long create(AppAddRequest request, User actor) {
        return appProvisioningService.create(request, actor);
    }

    public Long copy(Long sourceAppId, User actor) {
        return appProvisioningService.copy(sourceAppId, actor);
    }

    /**
 * 更新名称。
 *
 * @param request 请求参数
 * @param actor 操作发起人
 */
    @CacheEvict(value = "good_app_page", allEntries = true)
    public void updateName(AppUpdateRequest request, User actor) {
        App existingApp = requireExistingApp(request.getId());
        appAccessPolicy.requireOwner(existingApp, actor, "无权限修改该应用");

        String appName = normalizeRequiredName(request.getAppName());
        appPersistenceService.updateName(existingApp.getId(), appName, LocalDateTime.now());
    }

    /**
 * 删除应用管理应用。
 *
 * @param appId 应用编号
 * @param actor 操作发起人
 */
    @CacheEvict(value = "good_app_page", allEntries = true)
    public void delete(Long appId, User actor) {
        App existingApp = requireExistingApp(appId);
        appAccessPolicy.requireControlPermission(
                existingApp, actor, GenerationControlPermission.APP_DELETE,
                "无权限删除该应用");
        appDeletionService.delete(existingApp.getId());
    }

    /**
 * 更新{@code As}{@code Administrator}。
 *
 * @param request 请求参数
 */
    @CacheEvict(value = "good_app_page", allEntries = true)
    public void updateAsAdministrator(AppAdminUpdateRequest request) {
        App existingApp = requireExistingApp(request.getId());
        ThrowUtils.throwIf(request.getAppName() == null
                        && request.getCover() == null
                        && request.getPriority() == null,
                ErrorCode.PARAMS_ERROR, "至少提供一个待更新字段");
        String appName = null;
        if (request.getAppName() != null) {
            appName = normalizeRequiredName(request.getAppName());
        }
        String cover = request.getCover() == null ? null : StrUtil.trim(request.getCover());
        appPersistenceService.updateAdministrationFields(
                existingApp.getId(),
                appName,
                cover,
                request.getPriority(),
                LocalDateTime.now()
        );
    }

    /**
 * 删除{@code As}{@code Administrator}。
 *
 * @param appId 应用编号
 */
    @CacheEvict(value = "good_app_page", allEntries = true)
    public void deleteAsAdministrator(Long appId) {
        App existingApp = requireExistingApp(appId);
        appDeletionService.delete(existingApp.getId());
    }

    private App requireExistingApp(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        App app = appPersistenceService.findActiveById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        return app;
    }

    private String normalizeRequiredName(String appName) {
        String normalizedName = StrUtil.trim(appName);
        ThrowUtils.throwIf(StrUtil.isBlank(normalizedName), ErrorCode.PARAMS_ERROR, "应用名称不能为空");
        ThrowUtils.throwIf(normalizedName.length() > MAX_APP_NAME_LENGTH,
                ErrorCode.PARAMS_ERROR, "应用名称不能超过 50 个字符");
        return normalizedName;
    }
}
