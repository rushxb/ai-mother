package com.rush.rushaicodemother.application.chathistory;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.model.entity.ChatHistory;
import com.rush.rushaicodemother.model.vo.ChatHistoryAdminVO;
import com.rush.rushaicodemother.model.vo.ChatHistoryCursorPageVO;
import com.rush.rushaicodemother.model.vo.ChatHistoryVO;
import com.rush.rushaicodemother.service.chathistory.ChatHistorySlice;
import org.springframework.stereotype.Component;

import java.util.List;

/** 对话历史持久化实体到 API 白名单视图的唯一转换边界。 */
@Component
public class ChatHistoryViewAssembler {

    /**
 * 将当前对象转换为{@code Cursor}页面。
 *
 * @param slice 分页切片
 * @return {@code Cursor}页面
 */
    public ChatHistoryCursorPageVO toCursorPage(ChatHistorySlice slice) {
        List<ChatHistoryVO> records = slice.records().stream()
                .map(this::toUserView)
                .toList();
        ChatHistory lastRecord = slice.records().isEmpty()
                ? null
                : slice.records().getLast();
        return ChatHistoryCursorPageVO.builder()
                .records(records)
                .hasMore(slice.hasMore())
                .nextCursorCreateTime(lastRecord == null ? null : lastRecord.getCreateTime())
                .nextCursorId(lastRecord == null ? null : lastRecord.getId())
                .build();
    }

    /**
 * 将当前对象转换为管理端页面。
 *
 * @param sourcePage 来源分页数据
 * @return 管理端页面
 */
    public Page<ChatHistoryAdminVO> toAdminPage(Page<ChatHistory> sourcePage) {
        Page<ChatHistoryAdminVO> targetPage = new Page<>(
                sourcePage.getPageNumber(),
                sourcePage.getPageSize(),
                sourcePage.getTotalRow()
        );
        targetPage.setRecords(sourcePage.getRecords().stream()
                .map(this::toAdminView)
                .toList());
        return targetPage;
    }

    private ChatHistoryVO toUserView(ChatHistory history) {
        return ChatHistoryVO.builder()
                .id(history.getId())
                .message(history.getMessage())
                .messageType(history.getMessageType())
                .appId(history.getAppId())
                .createTime(history.getCreateTime())
                .build();
    }

    private ChatHistoryAdminVO toAdminView(ChatHistory history) {
        return ChatHistoryAdminVO.builder()
                .id(history.getId())
                .message(history.getMessage())
                .messageType(history.getMessageType())
                .appId(history.getAppId())
                .userId(history.getUserId())
                .createTime(history.getCreateTime())
                .updateTime(history.getUpdateTime())
                .build();
    }
}
