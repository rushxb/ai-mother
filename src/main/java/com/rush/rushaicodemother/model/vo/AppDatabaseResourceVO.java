package com.rush.rushaicodemother.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 应用 Database 资源封装类。
 */
@Data
public class AppDatabaseResourceVO implements Serializable {

    private Long id;

    private Long appId;

    private String resourceId;

    private String resourceName;

    private String databaseUrl;

    private String dbEngine;

    private String backendRuntime;

    private String sqlExecutionPolicy;

    private String status;

    private LocalDateTime lastUsedTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Boolean enabled;

    private static final long serialVersionUID = 1L;
}
