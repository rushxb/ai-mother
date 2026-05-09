package com.yupi.yuaicodemother.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用复制请求
 */
@Data
public class AppCopyRequest implements Serializable {

    /**
     * 源应用 ID
     */
    private Long sourceAppId;
}
