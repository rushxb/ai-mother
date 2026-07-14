package com.rush.rushaicodemother.service;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.model.dto.chathistory.ChatHistoryQueryRequest;
import com.rush.rushaicodemother.model.entity.ChatHistory;
import com.rush.rushaicodemother.service.chathistory.ChatHistorySlice;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.time.LocalDateTime;

/**
 * 对话历史 服务层。
 *
 *
 */
public interface ChatHistoryService {

    /**
     * 添加对话历史
     *
     * @param appId       应用 id
     * @param message     消息
     * @param messageType 消息类型
     * @param userId      用户 id
     */
    void addChatMessage(Long appId, String message, String messageType, Long userId);

    /**
     * 原子复制应用的有效对话历史。
     *
     * @param sourceAppId  源应用 ID
     * @param targetAppId  目标应用 ID
     * @param targetUserId 目标用户 ID
     */
    void copyByAppId(Long sourceAppId, Long targetAppId, Long targetUserId);

    /**
     * 使用稳定复合游标查询应用对话历史。
     *
     * @param appId               应用 ID
     * @param pageSize            单页记录数
     * @param lastCreateTime      上一页最后一条记录的创建时间
     * @param lastId              上一页最后一条记录的 ID
     * @return 对话历史切片
     */
    ChatHistorySlice listForApp(Long appId,
                                int pageSize,
                                LocalDateTime lastCreateTime,
                                Long lastId);

    /**
     * 管理端分页查询对话历史。
     *
     * @param queryRequest 查询条件
     * @return 实体分页，仅供应用层转换为管理视图
     */
    Page<ChatHistory> pageForAdministration(ChatHistoryQueryRequest queryRequest);

    /**
     * 加载对话历史到内存
     *
     * @param appId 应用 ID
     * @param chatMemory 对话记忆
     * @param maxCount 最多加载多少条
     * @return 加载成功的条数
     */
    int loadChatHistoryToMemory(Long appId, MessageWindowChatMemory chatMemory, int maxCount);
}
