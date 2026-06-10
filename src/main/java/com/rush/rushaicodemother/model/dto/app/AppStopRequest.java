package com.rush.rushaicodemother.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 停止应用生成请求
 */
@Data
public class AppStopRequest implements Serializable {

    /**
     * 应用 ID
     */
    private Long appId;

    private static final long serialVersionUID = 1L;
}
