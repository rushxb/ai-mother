package com.rush.rushaicodemother.application.chathistory;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.application.app.AppAccessPolicy;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.dto.chathistory.ChatHistoryQueryRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.ChatHistory;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.model.vo.ChatHistoryAdminVO;
import com.rush.rushaicodemother.model.vo.ChatHistoryCursorPageVO;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.chathistory.ChatHistorySlice;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHistoryQueryApplicationServiceTest {

    private final AppPersistenceService appPersistenceService = mock(AppPersistenceService.class);
    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
    private final TenantAuthorizationService tenantAuthorizationService =
            mock(TenantAuthorizationService.class);
    private final ChatHistoryQueryApplicationService service = new ChatHistoryQueryApplicationService(
            appPersistenceService,
            chatHistoryService,
            new AppAccessPolicy(tenantAuthorizationService),
            new ChatHistoryViewAssembler()
    );

    @Test
    void ownerCanQueryHistoryWithStableCursorAndSafeView() {
        Long appId = 10L;
        User owner = User.builder().id(20L).build();
        LocalDateTime cursor = LocalDateTime.of(2026, 7, 13, 10, 30);
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 13, 10, 20);
        App app = App.builder().id(appId).userId(owner.getId()).tenantId(100L).build();
        ChatHistory history = ChatHistory.builder()
                .id(90L)
                .appId(appId)
                .userId(owner.getId())
                .message("hello")
                .messageType("user")
                .createTime(createdAt)
                .isDelete(0)
                .build();

        when(appPersistenceService.findActiveById(appId)).thenReturn(app);
        when(chatHistoryService.listForApp(appId, 25, cursor, 99L))
                .thenReturn(new ChatHistorySlice(List.of(history), false));

        ChatHistoryCursorPageVO result = service.listForApp(appId, 25, cursor, 99L, owner);

        assertEquals(1, result.getRecords().size());
        assertEquals("hello", result.getRecords().getFirst().getMessage());
        assertEquals(90L, result.getNextCursorId());
        assertEquals(createdAt, result.getNextCursorCreateTime());
        assertFalse(result.isHasMore());
        verify(chatHistoryService).listForApp(appId, 25, cursor, 99L);
    }

    @Test
    void unrelatedUserCannotQueryHistory() {
        Long appId = 10L;
        App app = App.builder().id(appId).userId(20L).tenantId(100L).build();
        User actor = User.builder().id(30L).build();
        when(appPersistenceService.findActiveById(appId)).thenReturn(app);
        doThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR, "denied"))
                .when(tenantAuthorizationService)
                .requireRole(org.mockito.ArgumentMatchers.eq(100L),
                        org.mockito.ArgumentMatchers.eq(30L),
                        org.mockito.ArgumentMatchers.eq(TenantRole.ADMIN),
                        anyString());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.listForApp(appId, 10, null, null, actor)
        );

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        verify(chatHistoryService, never()).listForApp(any(), anyInt(), any(), any());
    }

    @Test
    void invalidPageSizeIsRejectedBeforeDatabaseAccess() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.listForApp(10L, 51, null, null, User.builder().id(20L).build())
        );

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        verify(appPersistenceService, never()).findActiveById(any());
    }

    @Test
    void administratorQueryMustDelegateAndReturnAdminWhitelistView() {
        ChatHistoryQueryRequest request = new ChatHistoryQueryRequest();
        ChatHistory history = ChatHistory.builder()
                .id(1L)
                .message("hello")
                .messageType("ai")
                .appId(2L)
                .userId(3L)
                .isDelete(0)
                .build();
        Page<ChatHistory> entityPage = new Page<>(1, 10, 1);
        entityPage.setRecords(List.of(history));
        when(chatHistoryService.pageForAdministration(request)).thenReturn(entityPage);

        Page<ChatHistoryAdminVO> result = service.listForAdministration(request);

        assertEquals(1, result.getRecords().size());
        assertEquals(3L, result.getRecords().getFirst().getUserId());
        verify(chatHistoryService).pageForAdministration(request);
    }
}
