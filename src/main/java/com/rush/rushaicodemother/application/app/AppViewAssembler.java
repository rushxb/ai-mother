package com.rush.rushaicodemother.application.app;

import cn.hutool.core.bean.BeanUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;
import com.rush.rushaicodemother.model.vo.OwnerAppVO;
import com.rush.rushaicodemother.model.vo.PublicAppVO;
import com.rush.rushaicodemother.model.vo.PublicUserSummaryVO;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import com.rush.rushaicodemother.service.user.UserDirectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用视图装配器。
 *
 * <p>集中负责实体到视图对象的转换和关联数据批量加载，不承担端口分配、持久化更新等写操作。</p>
 */
@Component
@RequiredArgsConstructor
public class AppViewAssembler {

    private final UserDirectoryService userDirectoryService;
    private final AppDatabaseResourceService appDatabaseResourceService;

    /**
 * 将当前对象转换为视图。
 *
 * @param app 应用
 * @return 视图
 */
    public OwnerAppVO toSensitiveView(App app) {
        OwnerAppVO appVO = copySensitiveBaseFields(app);
        if (appVO == null) {
            return null;
        }
        Long appId = app.getId();
        if (appId != null) {
            appVO.setDatabaseResource(appDatabaseResourceService.findActiveResourceView(appId));
        }

        Long userId = app.getUserId();
        if (userId != null) {
            appVO.setUser(userDirectoryService.findActivePublicSummary(userId));
        }
        return appVO;
    }

    /**
 * 将当前对象转换为视图列表。
 *
 * @param apps 应用列表
 * @return 视图列表集合
 */
    public List<OwnerAppVO> toSensitiveViewList(List<App> apps) {
        if (apps == null || apps.isEmpty()) {
            return new ArrayList<>();
        }
        List<App> validApps = apps.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (validApps.isEmpty()) {
            return new ArrayList<>();
        }

        Map<Long, PublicUserSummaryVO> userSummaryMap = loadPublicSummaryMap(validApps);
        Set<Long> appIds = validApps.stream()
                .map(App::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, AppDatabaseResourceVO> databaseResourceMap =
                appDatabaseResourceService.findActiveResourceViews(appIds);

        return validApps.stream()
                .map(app -> assembleSensitiveView(app, userSummaryMap, databaseResourceMap))
                .collect(Collectors.toList());
    }

    /** 装配精选场景的最小公开视图，不触碰数据库资源。 */
    public PublicAppVO toPublicView(App app) {
        if (app == null) {
            return null;
        }
        PublicAppVO view = copyPublicBaseFields(app);
        if (app.getUserId() != null) {
            view.setUser(userDirectoryService.findActivePublicSummary(app.getUserId()));
        }
        return view;
    }

    /** 批量装配精选公开视图，关联用户使用单次目录查询。 */
    public List<PublicAppVO> toPublicViewList(List<App> apps) {
        if (apps == null || apps.isEmpty()) {
            return new ArrayList<>();
        }
        List<App> validApps = apps.stream()
                .filter(Objects::nonNull)
                .toList();
        if (validApps.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, PublicUserSummaryVO> summaries = loadPublicSummaryMap(validApps);
        return validApps.stream()
                .map(app -> {
                    PublicAppVO view = copyPublicBaseFields(app);
                    view.setUser(summaries.get(app.getUserId()));
                    return view;
                })
                .toList();
    }

    private Map<Long, PublicUserSummaryVO> loadPublicSummaryMap(List<App> apps) {
        Set<Long> userIds = apps.stream()
                .map(App::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userDirectoryService.findActivePublicSummaries(userIds);
    }

    /** 汇总相关数据并组装视图。 */
    private OwnerAppVO assembleSensitiveView(App app,
                                             Map<Long, PublicUserSummaryVO> userSummaryMap,
                                             Map<Long, AppDatabaseResourceVO> databaseResourceMap) {
        OwnerAppVO appVO = copySensitiveBaseFields(app);
        Long userId = app.getUserId();
        if (userId != null) {
            appVO.setUser(userSummaryMap.get(userId));
        }

        Long appId = app.getId();
        AppDatabaseResourceVO databaseResource = appId == null ? null : databaseResourceMap.get(appId);
        appVO.setDatabaseResource(databaseResource);
        return appVO;
    }

    private OwnerAppVO copySensitiveBaseFields(App app) {
        if (app == null) {
            return null;
        }
        OwnerAppVO appVO = new OwnerAppVO();
        BeanUtil.copyProperties(app, appVO);
        return appVO;
    }

    private PublicAppVO copyPublicBaseFields(App app) {
        PublicAppVO view = new PublicAppVO();
        view.setId(app.getId());
        view.setAppName(app.getAppName());
        view.setCover(app.getCover());
        view.setCodeGenType(app.getCodeGenType());
        view.setDeployKey(app.getDeployKey());
        view.setDeployedTime(app.getDeployedTime());
        view.setUserId(app.getUserId());
        view.setCreateTime(app.getCreateTime());
        return view;
    }
}
