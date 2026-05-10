package com.yupi.yuaicodemother.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.yupi.yuaicodemother.exception.ErrorCode;
import com.yupi.yuaicodemother.exception.ThrowUtils;
import com.yupi.yuaicodemother.mapper.AppDatabaseResourceMapper;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.entity.AppDatabaseResource;
import com.yupi.yuaicodemother.model.vo.AppDatabaseResourceVO;
import com.yupi.yuaicodemother.service.AppDatabaseResourceService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 应用 Database 资源服务实现。
 */
@Service
public class AppDatabaseResourceServiceImpl
        extends ServiceImpl<AppDatabaseResourceMapper, AppDatabaseResource>
        implements AppDatabaseResourceService {

    private static final String STATUS_ACTIVE = "active";
    private static final String DB_ENGINE_SQL_LITE = "SqlLite";
    private static final String BACKEND_RUNTIME_GO = "go";
    private static final String DEFAULT_SQL_POLICY = "ask_every_time";
    private static final String DATABASE_DOMAIN = "database.nocode.cn";

    @Override
    public AppDatabaseResource enableDatabase(App app) {
        ThrowUtils.throwIf(app == null || app.getId() == null, ErrorCode.PARAMS_ERROR, "应用不存在");
        AppDatabaseResource existed = getByAppId(app.getId());
        if (existed != null) {
            if (!STATUS_ACTIVE.equals(existed.getStatus())) {
                existed.setStatus(STATUS_ACTIVE);
                existed.setLastUsedTime(LocalDateTime.now());
                this.updateById(existed);
            }
            return existed;
        }
        String resourceId = "db" + app.getId();
        AppDatabaseResource resource = AppDatabaseResource.builder()
                .appId(app.getId())
                .userId(app.getUserId())
                .resourceId(resourceId)
                .resourceName(buildResourceName(app))
                .databaseUrl("https://" + resourceId + "." + DATABASE_DOMAIN)
                .dbEngine(DB_ENGINE_SQL_LITE)
                .backendRuntime(BACKEND_RUNTIME_GO)
                .sqlExecutionPolicy(DEFAULT_SQL_POLICY)
                .status(STATUS_ACTIVE)
                .lastUsedTime(LocalDateTime.now())
                .build();
        boolean saved = this.save(resource);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "Database 资源启用失败");
        return resource;
    }

    @Override
    public AppDatabaseResource getByAppId(Long appId) {
        if (appId == null || appId <= 0) {
            return null;
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("appId", appId)
                .eq("status", STATUS_ACTIVE)
                .orderBy("createTime", false);
        return this.list(queryWrapper).stream().findFirst().orElse(null);
    }

    @Override
    public AppDatabaseResourceVO getResourceVO(AppDatabaseResource resource) {
        if (resource == null) {
            return null;
        }
        AppDatabaseResourceVO resourceVO = new AppDatabaseResourceVO();
        BeanUtil.copyProperties(resource, resourceVO);
        resourceVO.setEnabled(STATUS_ACTIVE.equals(resource.getStatus()));
        return resourceVO;
    }

    @Override
    public boolean shouldEnableForPrompt(String userMessage) {
        String normalized = StrUtil.blankToDefault(userMessage, "").toLowerCase();
        if (StrUtil.isBlank(normalized)) {
            return false;
        }
        return normalized.contains("database")
                || normalized.contains("数据库")
                || normalized.contains("sqlite")
                || normalized.contains("sqllite")
                || normalized.contains("sql lite")
                || (normalized.contains("后端") && (normalized.contains("数据") || normalized.contains("接口") || normalized.contains("api")))
                || (normalized.contains("backend") && (normalized.contains("data") || normalized.contains("api") || normalized.contains("sql")));
    }

    @Override
    public String appendGenerationInstructionIfEnabled(App app, String userMessage) {
        AppDatabaseResource resource = app == null ? null : getByAppId(app.getId());
        if (resource == null) {
            return userMessage;
        }
        return StrUtil.blankToDefault(userMessage, "") + "\n\n" + buildDatabaseInstruction(resource);
    }

    private String buildResourceName(App app) {
        String appName = StrUtil.blankToDefault(app.getAppName(), "未命名应用").trim();
        return StrUtil.sub(appName, 0, Math.min(appName.length(), 32)) + " Database";
    }

    private String buildDatabaseInstruction(AppDatabaseResource resource) {
        return """
                【Database 服务接入要求】
                当前应用已启用 NoCode Database 服务，本轮生成必须接入 Database。
                1. 后端服务技术选型固定为 Go + SqlLite，并放在独立 backend 目录下。
                2. 前端仍以现有 Vue 项目为主，通过 HTTP API 调用后端，不要把数据库读写逻辑硬编码在前端。
                3. Database URL：%s。
                4. SQL 执行策略：%s。涉及危险 SQL 或数据变更时，应在后端保留可审计、可确认的执行边界。
                5. 优先生成最小可运行闭环：Go 服务入口、SqlLite 初始化、基础连接封装、示例 API、前端调用适配。
                6. 不要破坏已有前端页面、路由、样式和构建脚本；如需新增脚本或说明，应保持工程结构清晰。
                """.formatted(resource.getDatabaseUrl(), resource.getSqlExecutionPolicy());
    }
}
