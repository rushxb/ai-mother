package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.ChatHistoryMapper;
import com.rush.rushaicodemother.model.entity.ChatHistory;
import com.rush.rushaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.data.message.ChatMessage;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHistoryServiceImplWriteIntegrityTest {

    private final ChatHistoryMapper chatHistoryMapper = mock(ChatHistoryMapper.class);
    private final ChatHistoryServiceImpl service = new ChatHistoryServiceImpl(chatHistoryMapper);

    @Test
    void addMessageMustWhitelistFieldsAndRequireExactlyOneInsertedRow() {
        when(chatHistoryMapper.insertSelective(any(ChatHistory.class))).thenReturn(1);

        service.addChatMessage(11L, "hello", ChatHistoryMessageTypeEnum.USER.getValue(), 21L);

        ArgumentCaptor<ChatHistory> entityCaptor = ArgumentCaptor.forClass(ChatHistory.class);
        verify(chatHistoryMapper).insertSelective(entityCaptor.capture());
        ChatHistory inserted = entityCaptor.getValue();
        assertEquals(11L, inserted.getAppId());
        assertEquals(21L, inserted.getUserId());
        assertEquals("hello", inserted.getMessage());
        assertEquals("user", inserted.getMessageType());
        assertNull(inserted.getId());
        assertNull(inserted.getCreateTime());
        assertNull(inserted.getUpdateTime());
        assertNull(inserted.getIsDelete());
    }

    @Test
    void addMessageMustNotSilentlyAcceptZeroAffectedRows() {
        when(chatHistoryMapper.insertSelective(any(ChatHistory.class))).thenReturn(0);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.addChatMessage(11L, "hello", "ai", 21L)
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
    }

    @Test
    void invalidMessageTypeMustBeRejectedBeforePersistence() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.addChatMessage(11L, "hello", "system", 21L)
        );

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        verify(chatHistoryMapper, never()).insertSelective(any(ChatHistory.class));
    }

    @Test
    void copyMustUseOneAtomicMapperStatementAndTreatEmptySourceAsSuccess() {
        when(chatHistoryMapper.copyActiveHistory(11L, 12L, 21L)).thenReturn(0);

        service.copyByAppId(11L, 12L, 21L);

        verify(chatHistoryMapper).copyActiveHistory(11L, 12L, 21L);
    }

    @Test
    void copyMustRejectSameSourceAndTarget() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.copyByAppId(11L, 11L, 21L)
        );

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        verify(chatHistoryMapper, never()).copyActiveHistory(any(), any(), any());
    }

    @Test
    void copySqlMustExcludeDeletedRowsAndProtectedColumns() throws NoSuchMethodException {
        Method method = ChatHistoryMapper.class.getMethod(
                "copyActiveHistory", Long.class, Long.class, Long.class
        );
        String sql = String.join(" ", method.getAnnotation(Insert.class).value())
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("insert into chat_history (message, messagetype, appid, userid)"));
        assertTrue(sql.contains("isdelete = 0"));
        assertTrue(sql.contains("order by createtime asc, id asc"));
        String insertedColumns = sql.substring(0, sql.indexOf("select"));
        assertFalse(insertedColumns.contains("createtime"));
        assertFalse(insertedColumns.contains("updatetime"));
    }

    @Test
    void memoryLoadArgumentsMustBeValidatedBeforeQuery() {
        BusinessException nullMemory = assertThrows(
                BusinessException.class,
                () -> service.loadChatHistoryToMemory(11L, null, 10)
        );
        BusinessException excessiveCount = assertThrows(
                BusinessException.class,
                () -> service.loadChatHistoryToMemory(
                        11L,
                        mock(MessageWindowChatMemory.class),
                        201
                )
        );

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), nullMemory.getCode());
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), excessiveCount.getCode());
        verify(chatHistoryMapper, never()).selectListByQuery(any());
    }

    @Test
    void memoryLoadMustClearStaleStateAndSkipUnknownMessageTypes() {
        MessageWindowChatMemory chatMemory = mock(MessageWindowChatMemory.class);
        when(chatHistoryMapper.selectListByQuery(any())).thenReturn(List.of(
                ChatHistory.builder().id(3L).message("latest").messageType("ai").build(),
                ChatHistory.builder().id(2L).message("ignored").messageType("system").build(),
                ChatHistory.builder().id(1L).message("oldest").messageType("user").build()
        ));

        int loadedCount = service.loadChatHistoryToMemory(11L, chatMemory, 10);

        assertEquals(2, loadedCount);
        verify(chatMemory).clear();
        verify(chatMemory, times(2)).add(any(ChatMessage.class));
    }
}
