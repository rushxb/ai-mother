package com.rush.rushaicodemother.mapper;

import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.model.entity.ChatHistory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 对话历史 映射层。
 *
 *
 */
public interface ChatHistoryMapper extends BaseMapper<ChatHistory> {

    @Delete("delete from chat_history where appId = #{appId}")
    int hardDeleteByAppId(@Param("appId") Long appId);
}
