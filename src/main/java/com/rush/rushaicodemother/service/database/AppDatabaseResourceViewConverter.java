package com.rush.rushaicodemother.service.database;

import com.rush.rushaicodemother.model.entity.AppDatabaseResource;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;
import org.springframework.stereotype.Component;

/** 将 Database 持久化实体显式转换为对外视图，避免实体越过业务边界。 */
@Component
public class AppDatabaseResourceViewConverter {

    private static final String STATUS_ACTIVE = "active";

    /**
 * 将当前对象转换为视图。
 *
 * @param resource 资源
 * @return 视图
 */
    public AppDatabaseResourceVO toView(AppDatabaseResource resource) {
        if (resource == null) {
            return null;
        }
        AppDatabaseResourceVO view = new AppDatabaseResourceVO();
        view.setId(resource.getId());
        view.setAppId(resource.getAppId());
        view.setResourceId(resource.getResourceId());
        view.setResourceName(resource.getResourceName());
        view.setDatabaseUrl(resource.getDatabaseUrl());
        view.setDbEngine(resource.getDbEngine());
        view.setBackendRuntime(resource.getBackendRuntime());
        view.setSqlExecutionPolicy(resource.getSqlExecutionPolicy());
        view.setStatus(resource.getStatus());
        view.setLastUsedTime(resource.getLastUsedTime());
        view.setCreateTime(resource.getCreateTime());
        view.setUpdateTime(resource.getUpdateTime());
        view.setEnabled(STATUS_ACTIVE.equals(resource.getStatus()));
        return view;
    }
}
