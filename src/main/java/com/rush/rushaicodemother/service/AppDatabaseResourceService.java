package com.rush.rushaicodemother.service;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;

import java.util.Collection;
import java.util.Map;

/**
 * 应用 Database 资源业务服务。
 *
 * <p>对外仅暴露场景化操作和视图对象，禁止泄漏持久化实体或通用 CRUD。</p>
 */
public interface AppDatabaseResourceService {

    /** 启用或幂等返回应用的 Database 资源。 */
    AppDatabaseResourceVO enableDatabase(App app);

    /** 查询应用当前启用的 Database 资源视图。 */
    AppDatabaseResourceVO findActiveResourceView(Long appId);

    /** 批量查询当前启用的 Database 资源视图，并按应用 ID 建立索引。 */
    Map<Long, AppDatabaseResourceVO> findActiveResourceViews(Collection<Long> appIds);

    /** 如果应用已启用 Database，则为代码生成追加后端与数据库约束。 */
    String appendGenerationInstructionIfEnabled(App app, String userMessage);
}
