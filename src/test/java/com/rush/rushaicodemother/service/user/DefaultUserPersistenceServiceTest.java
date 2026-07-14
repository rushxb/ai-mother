package com.rush.rushaicodemother.service.user;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.UserMapper;
import com.rush.rushaicodemother.model.dto.user.UserQueryRequest;
import com.rush.rushaicodemother.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultUserPersistenceServiceTest {

    private UserMapper userMapper;
    private DefaultUserPersistenceService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        service = new DefaultUserPersistenceService(userMapper);
    }

    @Test
    void batchLookupMustRemoveNullInvalidAndDuplicateIdsBeforeMapperInvocation() {
        when(userMapper.selectActiveByIds(List.of(2L, 1L))).thenReturn(List.of());

        service.findActiveByIds(Arrays.asList(null, 2L, -1L, 1L, 2L, 0L));

        verify(userMapper).selectActiveByIds(List.of(2L, 1L));
    }

    @Test
    void emptyBatchLookupMustNotInvokeMapper() {
        assertEquals(List.of(), service.findActiveByIds(Arrays.asList(null, -1L, 0L)));

        verify(userMapper, never()).selectActiveByIds(any());
    }

    @Test
    void createMustUseExplicitAllowedFieldsAndRequireGeneratedId() {
        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(101L);
            return 1;
        }).when(userMapper).insertUser(any(User.class));

        long userId = service.createUser(new UserPersistenceService.NewUser(
                "new-user",
                "password-hash",
                "New User",
                "avatar",
                "profile",
                "user",
                0L
        ));

        assertEquals(101L, userId);
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insertUser(userCaptor.capture());
        User inserted = userCaptor.getValue();
        assertEquals("new-user", inserted.getUserAccount());
        assertEquals("password-hash", inserted.getUserPassword());
        assertEquals("New User", inserted.getUserName());
        assertEquals("user", inserted.getUserRole());
        assertEquals(0L, inserted.getCreditBalance());
        assertNotNull(inserted.getId());
    }

    @Test
    void createMustMapUniqueConstraintFailureToStableBusinessError() {
        when(userMapper.insertUser(any(User.class))).thenThrow(new DuplicateKeyException("uk_userAccount"));

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.createUser(new UserPersistenceService.NewUser(
                        "duplicate-user",
                        "password-hash",
                        null,
                        null,
                        null,
                        "user",
                        0L
                )));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        assertEquals("账号重复", exception.getMessage());
    }

    @Test
    void updateMustReportMissingOrDeletedUser() {
        when(userMapper.updateActiveAdministrationFields(404L, "missing", null, null, null))
                .thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.updateAdministrationFields(404L, "missing", null, null, null));

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), exception.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageQueryMustAcceptMissingSortFieldAndDelegateThroughPersistenceBoundary() {
        UserQueryRequest request = new UserQueryRequest();
        Page<User> databasePage = Page.of(1, 10);
        when(userMapper.paginate(any(Page.class), any(QueryWrapper.class))).thenReturn(databasePage);

        assertEquals(databasePage, service.pageActiveUsers(request));
    }
}
