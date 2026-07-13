package com.rush.rushaicodemother.application.app;

import cn.hutool.core.bean.BeanUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.AppDatabaseResource;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.AppVO;
import com.rush.rushaicodemother.model.vo.UserVO;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import com.rush.rushaicodemother.service.UserService;
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

    private final UserService userService;
    private final AppDatabaseResourceService appDatabaseResourceService;

    public AppVO toView(App app) {
        AppVO appVO = copyBaseFields(app);
        if (appVO == null) {
            return null;
        }
        Long appId = app.getId();
        if (appId != null) {
            AppDatabaseResource databaseResource = appDatabaseResourceService.getByAppId(appId);
            appVO.setDatabaseResource(appDatabaseResourceService.getResourceVO(databaseResource));
        }

        Long userId = app.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            appVO.setUser(userService.getUserVO(user));
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
        Map<Long, AppDatabaseResource> databaseResourceMap =
                appDatabaseResourceService.getActiveResourceMapByAppIds(appIds);

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
        return userService.listByIds(userIds).stream()
                .filter(Objects::nonNull)
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(
                        User::getId,
                        userService::getUserVO,
                        (existing, ignored) -> existing
                ));
    }

    private AppVO assembleView(App app,
                               Map<Long, UserVO> userVOMap,
                               Map<Long, AppDatabaseResource> databaseResourceMap) {
        AppVO appVO = copyBaseFields(app);
        Long userId = app.getUserId();
        if (userId != null) {
            appVO.setUser(userVOMap.get(userId));
        }

        Long appId = app.getId();
        AppDatabaseResource databaseResource = appId == null ? null : databaseResourceMap.get(appId);
        appVO.setDatabaseResource(appDatabaseResourceService.getResourceVO(databaseResource));
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
