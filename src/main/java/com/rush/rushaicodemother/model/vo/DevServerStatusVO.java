package com.rush.rushaicodemother.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dev Server 状态响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevServerStatusVO {

    /**
     * 应用ID
     */
    private Long appId;

    /**
     * 是否运行中
     */
    private Boolean running;

    /**
     * 端口号
     */
    private Integer port;

    /**
     * 预览地址
     */
    private String previewUrl;

    /**
     * 状态描述
     */
    private String status;
}
