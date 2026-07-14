package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话历史游标分页响应。
 *
 * <p>客户端必须原样传回 nextCursorCreateTime 与 nextCursorId，复合游标可避免
 * 同一秒内多条消息造成重复或漏读。</p>
 */
@Value
@Builder
public class ChatHistoryCursorPageVO {

    @Builder.Default
    List<ChatHistoryVO> records = List.of();

    boolean hasMore;
    LocalDateTime nextCursorCreateTime;
    Long nextCursorId;
}
