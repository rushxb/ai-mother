package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** 用户可见的对话历史视图，仅暴露聊天展示所需字段。 */
@Value
@Builder
public class ChatHistoryVO {

    Long id;
    String message;
    String messageType;
    Long appId;
    LocalDateTime createTime;
}
