package com.yupi.yuaicodemother.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用对话生成请求
 */
@Data
public class AppChatRequest implements Serializable {

    /**
     * 应用 ID
     */
    private Long appId;

    /**
     * 用户消息
     */
    private String message;

    private static final long serialVersionUID = 1L;
}
