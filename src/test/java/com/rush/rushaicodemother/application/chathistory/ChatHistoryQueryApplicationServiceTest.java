package com.rush.rushaicodemother.application.chathistory;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.rush.rushaicodemother.application.app.AppAccessPolicy;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.dto.chathistory.ChatHistoryQueryRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.ChatHistory;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHistoryQueryApplicationServiceTest {

    private final AppService appService = mock(AppService.class);
    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
    private final ChatHistoryQueryApplicationService service = new ChatHistoryQueryApplicationService(
            appService,
            chatHistoryService,
            new AppAccessPolicy()
    );

    @Test
    void ownerCanQueryHistoryWithCursorAndBoundedPageSize() {
        Long appId = 10L;
        User owner = User.builder().id(20L).build();
        LocalDateTime cursor = LocalDateTime.of(2026, 7, 13, 10, 30);
        App app = App.builder().id(appId).userId(owner.getId()).build();
        QueryWrapper queryWrapper = QueryWrapper.create();
        Page<ChatHistory> expectedPage = Page.of(1, 25);

        when(appService.getById(appId)).thenReturn(app);
        when(chatHistoryService.getQueryWrapper(any(ChatHistoryQueryRequest.class))).thenReturn(queryWrapper);
        when(chatHistoryService.page(
                org.mockito.ArgumentMatchers.<Page<ChatHistory>>any(),
                same(queryWrapper)
        ))
                .thenReturn(expectedPage);

        Page<ChatHistory> result = service.listForApp(appId, 25, cursor, owner);

        assertSame(expectedPage, result);
        verify(chatHistoryService).getQueryWrapper(argThat(request ->
                appId.equals(request.getAppId()) && cursor.equals(request.getLastCreateTime())
        ));
    }

    @Test
    void unrelatedUserCannotQueryHistory() {
        Long appId = 10L;
        App app = App.builder().id(appId).userId(20L).build();
        User actor = User.builder().id(30L).build();
        when(appService.getById(appId)).thenReturn(app);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.listForApp(appId, 10, null, actor)
        );

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        verify(chatHistoryService, never()).page(
                org.mockito.ArgumentMatchers.<Page<ChatHistory>>any(),
                any(QueryWrapper.class)
        );
    }

    @Test
    void invalidPageSizeIsRejectedBeforeDatabaseAccess() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.listForApp(10L, 51, null, User.builder().id(20L).build())
        );

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        verify(appService, never()).getById(any());
    }
}
