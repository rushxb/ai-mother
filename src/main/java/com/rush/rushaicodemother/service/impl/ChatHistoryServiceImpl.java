package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import com.rush.rushaicodemother.common.query.SortFieldWhitelist;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.mapper.ChatHistoryMapper;
import com.rush.rushaicodemother.model.dto.chathistory.ChatHistoryQueryRequest;
import com.rush.rushaicodemother.model.entity.ChatHistory;
import com.rush.rushaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.chathistory.ChatHistorySlice;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话历史 服务层实现。
 *
 *
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatHistoryServiceImpl implements ChatHistoryService {

    private static final int MAX_APP_PAGE_SIZE = 50;
    private static final int MAX_MEMORY_MESSAGE_COUNT = 200;

    /** API 排序字段到数据库列的显式映射，禁止客户端输入直接进入 ORDER BY。 */
    private static final SortFieldWhitelist SORT_FIELDS = SortFieldWhitelist.of("createTime", Map.of(
            "id", "id",
            "messageType", "messageType",
            "appId", "appId",
            "userId", "userId",
            "createTime", "createTime",
            "updateTime", "updateTime"
    ));

    private final ChatHistoryMapper chatHistoryMapper;

    /**
 * 添加对话消息。
 *
 * @param appId 应用编号
 * @param message 消息内容
 * @param messageType 消息类型
 * @param userId 用户编号
 */
    @Override
    public void addChatMessage(Long appId, String message, String messageType, Long userId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(messageType), ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户 ID 不能为空");
        ChatHistoryMessageTypeEnum messageTypeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(messageType);
        ThrowUtils.throwIf(messageTypeEnum == null, ErrorCode.PARAMS_ERROR, "不支持的消息类型");

        ChatHistory chatHistory = ChatHistory.builder()
                .appId(appId)
                .message(message)
                .messageType(messageType)
                .userId(userId)
                .build();
        if (chatHistoryMapper.insertSelective(chatHistory) != 1) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存对话历史失败");
        }
    }

    /**
 * 复制按应用编号。
 *
 * @param sourceAppId 来源应用编号
 * @param targetAppId 目标应用编号
 * @param targetUserId 目标用户编号
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void copyByAppId(Long sourceAppId, Long targetAppId, Long targetUserId) {
        validatePositiveId(sourceAppId, "源应用 ID 不能为空");
        validatePositiveId(targetAppId, "目标应用 ID 不能为空");
        validatePositiveId(targetUserId, "目标用户 ID 不能为空");
        ThrowUtils.throwIf(sourceAppId.equals(targetAppId),
                ErrorCode.PARAMS_ERROR, "源应用和目标应用不能相同");

        // 影响行数为 0 表示源应用没有有效历史，是合法的幂等成功语义。
        chatHistoryMapper.copyActiveHistory(sourceAppId, targetAppId, targetUserId);
    }

    /**
 * 列出符合条件的{@code For}应用。
 *
 * @param appId 应用编号
 * @param pageSize {@code pageSize} 对应的调用参数
 * @param lastCreateTime {@code lastCreateTime} 对应的调用参数
 * @param lastId 目标资源编号
 * @return {@code For}应用
 */
    @Override
    public ChatHistorySlice listForApp(Long appId,
                                       int pageSize,
                                       LocalDateTime lastCreateTime,
                                       Long lastId) {
        validatePositiveId(appId, "应用 ID 不能为空");
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > MAX_APP_PAGE_SIZE,
                ErrorCode.PARAMS_ERROR, "页面大小必须在 1-50 之间");
        validateCursor(lastCreateTime, lastId);

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("appId", appId)
                .eq("isDelete", 0);
        appendCursorCondition(queryWrapper, lastCreateTime, lastId);
        queryWrapper.orderBy("createTime", false)
                .orderBy("id", false)
                .limit(1, pageSize + 1);

        List<ChatHistory> queriedRecords = chatHistoryMapper.selectListByQuery(queryWrapper);
        boolean hasMore = queriedRecords.size() > pageSize;
        List<ChatHistory> visibleRecords = hasMore
                ? queriedRecords.subList(0, pageSize)
                : queriedRecords;
        return new ChatHistorySlice(visibleRecords, hasMore);
    }

    /**
 * 返回{@code page}{@code For}{@code Administration}。
 *
 * @param queryRequest 查询请求
 * @return 对话历史服务{@code Impl}
 */
    @Override
    public Page<ChatHistory> pageForAdministration(ChatHistoryQueryRequest queryRequest) {
        ThrowUtils.throwIf(queryRequest == null, ErrorCode.PARAMS_ERROR, "查询条件不能为空");
        int pageNum = queryRequest.getPageNum();
        int pageSize = queryRequest.getPageSize();
        ThrowUtils.throwIf(pageNum <= 0, ErrorCode.PARAMS_ERROR, "页码必须大于 0");
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > 100,
                ErrorCode.PARAMS_ERROR, "页面大小必须在 1-100 之间");
        return chatHistoryMapper.paginate(
                Page.of(pageNum, pageSize),
                buildAdministrationQuery(queryRequest)
        );
    }

    /**
 * 加载对话历史{@code To}记忆。
 *
 * @param appId 应用编号
 * @param chatMemory 对话记忆
 * @param maxCount 最大数量
 * @return 计算或处理后的数值结果
 */
    @Override
    public int loadChatHistoryToMemory(Long appId, MessageWindowChatMemory chatMemory, int maxCount) {
        validatePositiveId(appId, "应用 ID 不能为空");
        ThrowUtils.throwIf(chatMemory == null, ErrorCode.PARAMS_ERROR, "对话记忆不能为空");
        ThrowUtils.throwIf(maxCount <= 0 || maxCount > MAX_MEMORY_MESSAGE_COUNT,
                ErrorCode.PARAMS_ERROR, "历史消息加载数量必须在 1-200 之间");

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("appId", appId)
                .eq("isDelete", 0)
                .orderBy("createTime", false)
                .orderBy("id", false)
                .limit(1, maxCount);
        List<ChatHistory> historyList;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            historyList = chatHistoryMapper.selectListByQuery(queryWrapper);
        } catch (RuntimeException exception) {
            // 数据库不可用时保留现有缓存，历史上下文加载失败不阻断核心生成流程。
            log.error("查询历史对话失败，appId: {}", appId, LogExceptionSanitizer.sanitize(exception));
            return 0;
        }

        List<ChatMessage> messages = toChronologicalMessages(appId, historyList);
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            // 查询成功后始终重建记忆，空历史也必须清除旧缓存。
            chatMemory.clear();
            for (ChatMessage message : messages) {
                chatMemory.add(message);
            }
            log.info("成功加载对话历史到记忆，appId: {}, loadedCount: {}", appId, messages.size());
            return messages.size();
        } catch (RuntimeException exception) {
            clearPartiallyRebuiltMemory(chatMemory, appId, exception);
            return 0;
        }
    }

    /** 构建并返回{@code Administration}查询。 */
    private QueryWrapper buildAdministrationQuery(ChatHistoryQueryRequest queryRequest) {
        String sortField = SORT_FIELDS.resolve(queryRequest.getSortField());
        boolean ascending = "ascend".equals(queryRequest.getSortOrder());
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("isDelete", 0)
                .eq("id", queryRequest.getId(), queryRequest.getId() != null)
                .like("message", queryRequest.getMessage(), StrUtil.isNotBlank(queryRequest.getMessage()))
                .eq("messageType", queryRequest.getMessageType(), StrUtil.isNotBlank(queryRequest.getMessageType()))
                .eq("appId", queryRequest.getAppId(), queryRequest.getAppId() != null)
                .eq("userId", queryRequest.getUserId(), queryRequest.getUserId() != null)
                .orderBy(sortField, ascending);
        if (!"id".equals(sortField)) {
            queryWrapper.orderBy("id", ascending);
        }
        return queryWrapper;
    }

    /** 将当前对象转换为{@code Chronological}消息。 */
    private List<ChatMessage> toChronologicalMessages(Long appId, List<ChatHistory> historyList) {
        List<ChatMessage> messages = new ArrayList<>(historyList.size());
        for (ChatHistory history : historyList.reversed()) {
            if (ChatHistoryMessageTypeEnum.USER.getValue().equals(history.getMessageType())) {
                messages.add(UserMessage.from(history.getMessage()));
            } else if (ChatHistoryMessageTypeEnum.AI.getValue().equals(history.getMessageType())) {
                messages.add(AiMessage.from(history.getMessage()));
            } else {
                log.warn("跳过未知类型的对话历史，appId: {}, historyId: {}, messageType: {}",
                        appId, history.getId(), history.getMessageType());
            }
        }
        return messages;
    }

    /** 清理{@code Partially}{@code Rebuilt}记忆。 */
    private void clearPartiallyRebuiltMemory(MessageWindowChatMemory chatMemory,
                                             Long appId,
                                             RuntimeException rebuildFailure) {
        log.error("重建历史对话记忆失败，appId: {}", appId, LogExceptionSanitizer.sanitize(rebuildFailure));
        try {
            chatMemory.clear();
        } catch (RuntimeException cleanupFailure) {
            rebuildFailure.addSuppressed(cleanupFailure);
            log.error("清理不完整的历史对话记忆失败，appId: {}", appId, LogExceptionSanitizer.sanitize(cleanupFailure));
        }
    }

    /** 追加{@code Cursor}{@code Condition}。 */
    private void appendCursorCondition(QueryWrapper queryWrapper,
                                       LocalDateTime lastCreateTime,
                                       Long lastId) {
        if (lastCreateTime == null) {
            return;
        }
        QueryColumn createTimeColumn = new QueryColumn("createTime");
        QueryColumn idColumn = new QueryColumn("id");
        queryWrapper.and(
                createTimeColumn.lt(lastCreateTime)
                        .or(createTimeColumn.eq(lastCreateTime)
                                .and(idColumn.lt(lastId)))
        );
    }

    private void validateCursor(LocalDateTime lastCreateTime, Long lastId) {
        boolean onlyOneCursorPartPresent = (lastCreateTime == null) != (lastId == null);
        ThrowUtils.throwIf(onlyOneCursorPartPresent,
                ErrorCode.PARAMS_ERROR, "游标时间和游标 ID 必须同时提供");
        if (lastId != null) {
            validatePositiveId(lastId, "游标 ID 必须大于 0");
        }
    }

    private void validatePositiveId(Long id, String message) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, message);
    }
}
