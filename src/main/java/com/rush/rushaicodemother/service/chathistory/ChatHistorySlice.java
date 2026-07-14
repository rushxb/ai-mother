package com.rush.rushaicodemother.service.chathistory;

import com.rush.rushaicodemother.model.entity.ChatHistory;

import java.util.List;

/**
 * 基于复合游标查询得到的对话历史切片。
 *
 * <p>切片不执行总数统计，避免在高频聊天历史查询中产生无意义的 COUNT 开销。</p>
 */
public record ChatHistorySlice(List<ChatHistory> records, boolean hasMore) {

    public ChatHistorySlice {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
