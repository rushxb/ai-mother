package com.rush.rushaicodemother.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用 Database 启用请求。
 */
@Data
public class AppDatabaseEnableRequest implements Serializable {

    /**
     * 应用 ID
     */
    private Long appId;

    private static final long serialVersionUID = 1L;
}
