package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** 管理端对话历史视图，不暴露逻辑删除等持久化内部字段。 */
@Value
@Builder
public class ChatHistoryAdminVO {

    Long id;
    String message;
    String messageType;
    Long appId;
    Long userId;
    LocalDateTime createTime;
    LocalDateTime updateTime;
}
