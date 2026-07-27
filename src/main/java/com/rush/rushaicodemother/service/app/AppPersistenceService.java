package com.rush.rushaicodemother.service.app;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.model.dto.app.AppQueryRequest;
import com.rush.rushaicodemother.model.entity.App;

import java.time.LocalDateTime;

/**
 * 应用元数据持久化边界。
 *
 * <p>只暴露经过业务场景约束的查询和字段级写入，禁止上层依赖 MyBatis-Flex 通用 CRUD，
 * 也禁止调用方传入任意实体决定更新字段。</p>
 */
public interface AppPersistenceService {

    /** 创建一个应用程序，其路由和类型已由调用者决定。 */
    long createPrepared(NewApp app);

    /** 查询未删除的应用。 */
    App findActiveById(Long appId);

    /** 按白名单条件分页查询未删除的应用。 */
    Page<App> pageActiveApps(AppQueryRequest queryRequest);

    /** 更新所有者可编辑的应用名称。 */
    void updateName(Long appId, String appName, LocalDateTime editTime);

    /** 更新管理员允许编辑的应用字段。 */
    void updateAdministrationFields(Long appId,
                                    String appName,
                                    String cover,
                                    Integer priority,
                                    LocalDateTime editTime);

    /** 持久化应用当前 Dev Server 端口。 */
    void updateDevServerPort(Long appId, int port);

    record NewApp(String appName,
                  String initPrompt,
                  String codeGenType,
                  int priority,
                  Long userId,
                  Long tenantId) {
    }
}
