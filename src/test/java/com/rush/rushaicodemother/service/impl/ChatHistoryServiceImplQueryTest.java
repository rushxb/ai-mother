package com.rush.rushaicodemother.service.impl;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.rush.rushaicodemother.mapper.ChatHistoryMapper;
import com.rush.rushaicodemother.model.dto.chathistory.ChatHistoryQueryRequest;
import com.rush.rushaicodemother.model.entity.ChatHistory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHistoryServiceImplQueryTest {

    private final ChatHistoryMapper chatHistoryMapper = mock(ChatHistoryMapper.class);
    private final ChatHistoryServiceImpl service = new ChatHistoryServiceImpl(chatHistoryMapper);

    @Test
    void missingSortFieldMustUseDefaultSortWithoutThrowing() {
        ChatHistoryQueryRequest request = new ChatHistoryQueryRequest();
        when(chatHistoryMapper.paginate(
                org.mockito.ArgumentMatchers.<Page<ChatHistory>>any(),
                any(QueryWrapper.class)
        ))
                .thenReturn(Page.of(1, 10));

        assertDoesNotThrow(() -> service.pageForAdministration(request));
    }

    @Test
    void applicationQueryMustUseStableCursorAndExcludeDeletedRows() {
        LocalDateTime cursorTime = LocalDateTime.of(2026, 7, 13, 10, 30);
        when(chatHistoryMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());

        service.listForApp(10L, 20, cursorTime, 99L);

        ArgumentCaptor<QueryWrapper> queryCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(chatHistoryMapper).selectListByQuery(queryCaptor.capture());
        String sql = queryCaptor.getValue().toSQL().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("appId".toLowerCase(Locale.ROOT)));
        assertTrue(sql.contains("isdelete"));
        assertTrue(sql.contains("createtime"));
        assertTrue(sql.contains("id"));
        assertTrue(sql.contains("limit") && sql.contains("21"));
    }
}
