package com.rush.rushaicodemother.service.database;

import java.time.LocalDateTime;

/**
 * 创建或恢复应用 Database 资源所需的受控字段。
 *
 * <p>该命令由业务服务构造，持久化层只允许写入这里明确列出的字段。</p>
 */
public record NewAppDatabaseResource(
        Long appId,
        Long userId,
        String resourceId,
        String resourceName,
        String databaseUrl,
        String dbEngine,
        String backendRuntime,
        String sqlExecutionPolicy,
        LocalDateTime lastUsedTime
) {
}
