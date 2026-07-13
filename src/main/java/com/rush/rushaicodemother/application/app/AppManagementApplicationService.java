package com.rush.rushaicodemother.application.app;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.dto.app.AppAddRequest;
import com.rush.rushaicodemother.model.dto.app.AppAdminUpdateRequest;
import com.rush.rushaicodemother.model.dto.app.AppUpdateRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.service.AppService;
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

    private final AppService appService;
    private final AppAccessPolicy appAccessPolicy;
    private final AppProvisioningService appProvisioningService;
    private final AppDeletionService appDeletionService;

    public Long create(AppAddRequest request, User actor) {
        return appProvisioningService.create(request, actor);
    }

    public Long copy(Long sourceAppId, User actor) {
        return appProvisioningService.copy(sourceAppId, actor);
    }

    @CacheEvict(value = "good_app_page", allEntries = true)
    public void updateName(AppUpdateRequest request, User actor) {
        App existingApp = requireExistingApp(request.getId());
        appAccessPolicy.requireOwner(existingApp, actor, "无权限修改该应用");

        String appName = normalizeRequiredName(request.getAppName());
        App update = new App();
        update.setId(existingApp.getId());
        update.setAppName(appName);
        update.setEditTime(LocalDateTime.now());
        boolean updated = appService.updateById(update);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "更新应用失败");
    }

    @CacheEvict(value = "good_app_page", allEntries = true)
    public void delete(Long appId, User actor) {
        App existingApp = requireExistingApp(appId);
        appAccessPolicy.requireOwnerOrAdmin(existingApp, actor, "无权限删除该应用");
        appDeletionService.delete(existingApp.getId());
    }

    @CacheEvict(value = "good_app_page", allEntries = true)
    public void updateAsAdministrator(AppAdminUpdateRequest request) {
        App existingApp = requireExistingApp(request.getId());
        App update = new App();
        update.setId(existingApp.getId());
        if (request.getAppName() != null) {
            update.setAppName(normalizeRequiredName(request.getAppName()));
        }
        if (request.getCover() != null) {
            update.setCover(StrUtil.trim(request.getCover()));
        }
        if (request.getPriority() != null) {
            update.setPriority(request.getPriority());
        }
        update.setEditTime(LocalDateTime.now());
        boolean updated = appService.updateById(update);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "更新应用失败");
    }

    @CacheEvict(value = "good_app_page", allEntries = true)
    public void deleteAsAdministrator(Long appId) {
        App existingApp = requireExistingApp(appId);
        appDeletionService.delete(existingApp.getId());
    }

    private App requireExistingApp(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        App app = appService.getById(appId);
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
