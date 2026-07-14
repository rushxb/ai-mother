package com.rush.rushaicodemother.mapper;

import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.model.entity.ChatHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 对话历史 映射层。
 *
 *
 */
public interface ChatHistoryMapper extends BaseMapper<ChatHistory> {

    /**
     * 单条 SQL 原子复制有效历史，只映射业务字段，不复制主键、审计时间和删除标记。
     */
    @Insert("""
            INSERT INTO chat_history (message, messageType, appId, userId)
            SELECT message, messageType, #{targetAppId}, #{targetUserId}
            FROM chat_history
            WHERE appId = #{sourceAppId}
              AND isDelete = 0
            ORDER BY createTime ASC, id ASC
            """)
    int copyActiveHistory(@Param("sourceAppId") Long sourceAppId,
                          @Param("targetAppId") Long targetAppId,
                          @Param("targetUserId") Long targetUserId);
}
