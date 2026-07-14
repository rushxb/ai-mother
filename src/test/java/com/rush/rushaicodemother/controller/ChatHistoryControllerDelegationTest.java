package com.rush.rushaicodemother.controller;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.application.chathistory.ChatHistoryQueryApplicationService;
import com.rush.rushaicodemother.model.dto.chathistory.ChatHistoryQueryRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.ChatHistoryAdminVO;
import com.rush.rushaicodemother.model.vo.ChatHistoryCursorPageVO;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHistoryControllerDelegationTest {

    private final ChatHistoryQueryApplicationService queryApplicationService =
            mock(ChatHistoryQueryApplicationService.class);
    private final UserService userService = mock(UserService.class);
    private final ChatHistoryController controller = new ChatHistoryController(
            queryApplicationService,
            userService
    );

    @Test
    void appQueryMustExtractActorAndDelegateWholeUseCase() {
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        User actor = User.builder().id(8L).build();
        LocalDateTime cursor = LocalDateTime.of(2026, 7, 13, 12, 0);
        ChatHistoryCursorPageVO expected = ChatHistoryCursorPageVO.builder().build();
        when(userService.getLoginUser(servletRequest)).thenReturn(actor);
        when(queryApplicationService.listForApp(10L, 20, cursor, 99L, actor))
                .thenReturn(expected);

        ChatHistoryCursorPageVO result = controller.listAppChatHistory(
                10L, 20, cursor, 99L, servletRequest
        ).getData();

        assertSame(expected, result);
        verify(queryApplicationService).listForApp(10L, 20, cursor, 99L, actor);
    }

    @Test
    void adminQueryMustNotBuildPersistenceQueryInController() {
        ChatHistoryQueryRequest request = new ChatHistoryQueryRequest();
        Page<ChatHistoryAdminVO> expected = Page.of(1, 10);
        when(queryApplicationService.listForAdministration(request)).thenReturn(expected);

        Page<ChatHistoryAdminVO> result = controller
                .listAllChatHistoryByPageForAdmin(request)
                .getData();

        assertSame(expected, result);
        verify(queryApplicationService).listForAdministration(request);
    }
}
