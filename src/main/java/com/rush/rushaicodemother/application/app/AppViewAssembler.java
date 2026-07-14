package com.rush.rushaicodemother.application.app;

import cn.hutool.core.bean.BeanUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;
import com.rush.rushaicodemother.model.vo.AppVO;
import com.rush.rushaicodemother.model.vo.UserVO;
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

    public AppVO toView(App app) {
        AppVO appVO = copyBaseFields(app);
        if (appVO == null) {
            return null;
        }
        Long appId = app.getId();
        if (appId != null) {
            appVO.setDatabaseResource(appDatabaseResourceService.findActiveResourceView(appId));
        }

        Long userId = app.getUserId();
        if (userId != null) {
            appVO.setUser(userDirectoryService.findActiveUserView(userId));
        }
        return appVO;
    }

    public List<AppVO> toViewList(List<App> apps) {
        if (apps == null || apps.isEmpty()) {
            return new ArrayList<>();
        }
        List<App> validApps = apps.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (validApps.isEmpty()) {
            return new ArrayList<>();
        }

        Map<Long, UserVO> userVOMap = loadUserVOMap(validApps);
        Set<Long> appIds = validApps.stream()
                .map(App::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, AppDatabaseResourceVO> databaseResourceMap =
                appDatabaseResourceService.findActiveResourceViews(appIds);

        return validApps.stream()
                .map(app -> assembleView(app, userVOMap, databaseResourceMap))
                .collect(Collectors.toList());
    }

    private Map<Long, UserVO> loadUserVOMap(List<App> apps) {
        Set<Long> userIds = apps.stream()
                .map(App::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userDirectoryService.findActiveUserViews(userIds);
    }

    private AppVO assembleView(App app,
                               Map<Long, UserVO> userVOMap,
                               Map<Long, AppDatabaseResourceVO> databaseResourceMap) {
        AppVO appVO = copyBaseFields(app);
        Long userId = app.getUserId();
        if (userId != null) {
            appVO.setUser(userVOMap.get(userId));
        }

        Long appId = app.getId();
        AppDatabaseResourceVO databaseResource = appId == null ? null : databaseResourceMap.get(appId);
        appVO.setDatabaseResource(databaseResource);
        return appVO;
    }

    private AppVO copyBaseFields(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        return appVO;
    }
}
