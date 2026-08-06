package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 应用 Database 资源的供应与代码生成约束配置。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.database-resource")
public class AppDatabaseResourceProperties {

    /** Database 访问地址协议。 */
    public static final String URL_SCHEME = "https";

    /** Database 资源域名。 */
    public static final String DOMAIN = "database.nocode.cn";

    /** 数据库引擎名称。 */
    public static final String DB_ENGINE = "SQLite";

    /** 代码生成要求的后端运行时。 */
    public static final String BACKEND_RUNTIME = "go";

    /** SQL 执行确认策略。 */
    public static final String SQL_EXECUTION_POLICY = "ask_every_time";

    /** Database 访问地址协议。 */
    @NotBlank
    @Pattern(regexp = "(?i)^https?$", message = "Database URL 协议仅支持 http 或 https")
    private String urlScheme = URL_SCHEME;

    /** Database 资源域名；允许使用 localhost 作为本地或生产部署配置。 */
    @NotBlank
    @Pattern(
            regexp = "(?i)^(localhost|(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*"
                    + "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)$",
            message = "Database 资源域名格式不合法"
    )
    private String domain = DOMAIN;

    /** 数据库引擎名称。 */
    @NotBlank
    private String dbEngine = DB_ENGINE;

    /** 代码生成时要求使用的后端运行时。 */
    @NotBlank
    private String backendRuntime = BACKEND_RUNTIME;

    /** SQL 执行确认策略。 */
    @NotBlank
    private String sqlExecutionPolicy = SQL_EXECUTION_POLICY;
}
