package com.rush.rushaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.AppDatabaseResourceProperties;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.AppDatabaseResource;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import com.rush.rushaicodemother.service.database.AppDatabaseResourcePersistenceService;
import com.rush.rushaicodemother.service.database.AppDatabaseResourceViewConverter;
import com.rush.rushaicodemother.service.database.NewAppDatabaseResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 应用 Database 资源业务服务实现。
 *
 * <p>负责资源命名、供应配置和生成提示词；所有数据库读写统一委托给场景化持久化边界。</p>
 */
@Service
@RequiredArgsConstructor
public class AppDatabaseResourceServiceImpl implements AppDatabaseResourceService {

    private static final int MAX_RESOURCE_NAME_PREFIX_LENGTH = 32;
    private static final String RESOURCE_ID_PREFIX = "db";

    private final AppDatabaseResourcePersistenceService persistenceService;
    private final AppDatabaseResourceViewConverter viewConverter;
    private final AppDatabaseResourceProperties properties;

    /**
 * 启用数据库。
 *
 * @param app 应用
 * @return 数据库
 */
    @Override
    public AppDatabaseResourceVO enableDatabase(App app) {
        validateApp(app);
        String resourceId = RESOURCE_ID_PREFIX + app.getId();
        NewAppDatabaseResource newResource = new NewAppDatabaseResource(
                app.getId(),
                app.getUserId(),
                resourceId,
                buildResourceName(app),
                buildDatabaseUrl(resourceId),
                properties.getDbEngine(),
                properties.getBackendRuntime(),
                properties.getSqlExecutionPolicy(),
                LocalDateTime.now()
        );
        return viewConverter.toView(persistenceService.enableResource(newResource));
    }

    /**
 * 查找匹配的活动资源视图。
 *
 * @param appId 应用编号
 * @return 活动资源视图
 */
    @Override
    public AppDatabaseResourceVO findActiveResourceView(Long appId) {
        if (!isValidId(appId)) {
            return null;
        }
        return viewConverter.toView(persistenceService.findActiveByAppId(appId));
    }

    /**
 * 查找匹配的活动资源{@code Views}。
 *
 * @param appIds 待处理的 {@code appIds} 集合
 * @return 活动资源{@code Views}集合
 */
    @Override
    public Map<Long, AppDatabaseResourceVO> findActiveResourceViews(Collection<Long> appIds) {
        List<AppDatabaseResource> resources = persistenceService.findActiveByAppIds(appIds);
        return resources.stream()
                .filter(Objects::nonNull)
                .filter(resource -> isValidId(resource.getAppId()))
                .collect(Collectors.toMap(
                        AppDatabaseResource::getAppId,
                        viewConverter::toView,
                        (existing, ignored) -> existing,
                        LinkedHashMap::new
                ));
    }

    /**
 * 追加生成指令{@code If}启用。
 *
 * @param app 应用
 * @param userMessage 用户消息
 * @return 处理后的生成指令{@code If}启用文本
 */
    @Override
    public String appendGenerationInstructionIfEnabled(App app, String userMessage) {
        if (app == null || !isValidId(app.getId())) {
            return userMessage;
        }
        AppDatabaseResource resource = persistenceService.findActiveByAppId(app.getId());
        if (resource == null) {
            return userMessage;
        }
        return StrUtil.blankToDefault(userMessage, "") + "\n\n" + buildDatabaseInstruction(resource);
    }

    private void validateApp(App app) {
        ThrowUtils.throwIf(app == null || !isValidId(app.getId()), ErrorCode.PARAMS_ERROR, "应用不存在");
        ThrowUtils.throwIf(!isValidId(app.getUserId()), ErrorCode.PARAMS_ERROR, "应用所属用户不存在");
    }

    private String buildResourceName(App app) {
        String appName = StrUtil.blankToDefault(app.getAppName(), "未命名应用").trim();
        int codePointCount = appName.codePointCount(0, appName.length());
        int endIndex = codePointCount <= MAX_RESOURCE_NAME_PREFIX_LENGTH
                ? appName.length()
                : appName.offsetByCodePoints(0, MAX_RESOURCE_NAME_PREFIX_LENGTH);
        String resourceNamePrefix = appName.substring(0, endIndex);
        return resourceNamePrefix + " Database";
    }

    private String buildDatabaseUrl(String resourceId) {
        String scheme = properties.getUrlScheme().trim().toLowerCase(Locale.ROOT);
        String domain = properties.getDomain().trim().toLowerCase(Locale.ROOT);
        return "%s://%s.%s".formatted(scheme, resourceId, domain);
    }

    /** 构建并返回数据库指令。 */
    private String buildDatabaseInstruction(AppDatabaseResource resource) {
        return """
                【Database 服务接入要求】
                当前应用已启用 Rush Database 服务，本轮生成必须接入 Database。
                1. 后端服务技术选型固定为 %s + %s，并放在独立 backend 目录下。
                2. 前端仍以现有 Vue 项目为主，通过 HTTP API 调用后端，不要把数据库读写逻辑硬编码在前端。
                3. Database URL：%s。
                4. SQL 执行策略：%s。涉及危险 SQL 或数据变更时，应在后端保留可审计、可确认的执行边界。
                5. 优先生成最小可运行闭环：后端服务入口、%s 初始化、基础连接封装、示例 API、前端调用适配。
                6. 不要破坏已有前端页面、路由、样式和构建脚本；如需新增脚本或说明，应保持工程结构清晰。
                """.formatted(
                resource.getBackendRuntime(),
                resource.getDbEngine(),
                resource.getDatabaseUrl(),
                resource.getSqlExecutionPolicy(),
                resource.getDbEngine()
        );
    }

    private boolean isValidId(Long id) {
        return id != null && id > 0;
    }
}
