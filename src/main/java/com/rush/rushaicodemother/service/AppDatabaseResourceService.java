package com.rush.rushaicodemother.service;

import com.mybatisflex.core.service.IService;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.AppDatabaseResource;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;

import java.util.Collection;
import java.util.Map;

/**
 * 应用 Database 资源服务。
 */
public interface AppDatabaseResourceService extends IService<AppDatabaseResource> {

    /**
     * 启用或返回已启用的 Database 资源。
     */
    AppDatabaseResource enableDatabase(App app);

    /**
     * 查询应用当前 Database 资源。
     */
    AppDatabaseResource getByAppId(Long appId);

    /**
     * 批量查询应用当前启用的 Database 资源，并按应用 ID 建立索引。
     *
     * @param appIds 应用 ID 集合
     * @return 应用 ID 到资源的映射；没有启用资源的应用不出现在结果中
     */
    Map<Long, AppDatabaseResource> getActiveResourceMapByAppIds(Collection<Long> appIds);

    /**
     * 转换为前端封装类。
     */
    AppDatabaseResourceVO getResourceVO(AppDatabaseResource resource);

    /**
     * 用户提示词是否明确要求启用 Database/后端数据库能力。
     */
    boolean shouldEnableForPrompt(String userMessage);

    /**
     * 如果应用已启用 Database，则为代码生成追加后端与数据库约束。
     */
    String appendGenerationInstructionIfEnabled(App app, String userMessage);
}
