package com.rush.rushaicodemother.service.user;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.model.dto.user.UserQueryRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.AdminUserVO;
import com.rush.rushaicodemother.model.vo.PublicUserSummaryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultUserDirectoryServiceTest {

    private UserPersistenceService userPersistenceService;
    private UserViewConverter userViewConverter;
    private DefaultUserDirectoryService service;

    @BeforeEach
    void setUp() {
        userPersistenceService = mock(UserPersistenceService.class);
        userViewConverter = mock(UserViewConverter.class);
        service = new DefaultUserDirectoryService(userPersistenceService, userViewConverter);
    }

    @Test
    void batchLookupMustReturnOnlyValidIndexedViews() {
        User firstUser = User.builder().id(1L).build();
        User duplicateUser = User.builder().id(1L).build();
        User invalidUser = User.builder().id(null).build();
        AdminUserVO firstView = new AdminUserVO();
        firstView.setId(1L);
        when(userPersistenceService.findActiveByIds(Arrays.asList(1L, null)))
                .thenReturn(Arrays.asList(firstUser, duplicateUser, invalidUser, null));
        when(userViewConverter.toAdminView(firstUser)).thenReturn(firstView);

        Map<Long, AdminUserVO> result = service.findActiveAdminViews(Arrays.asList(1L, null));

        assertEquals(Map.of(1L, firstView), result);
        verify(userViewConverter).toAdminView(firstUser);
    }

    @Test
    void pageLookupMustReturnVoPageWithoutExposingEntities() {
        UserQueryRequest request = new UserQueryRequest();
        User user = User.builder().id(7L).build();
        AdminUserVO userView = new AdminUserVO();
        userView.setId(7L);
        Page<User> userPage = new Page<>(2, 5, 11);
        userPage.setRecords(List.of(user));
        when(userPersistenceService.pageActiveUsers(request)).thenReturn(userPage);
        when(userViewConverter.toAdminView(user)).thenReturn(userView);

        Page<AdminUserVO> result = service.pageActiveAdminViews(request);

        assertEquals(2, result.getPageNumber());
        assertEquals(5, result.getPageSize());
        assertEquals(11, result.getTotalRow());
        assertSame(userView, result.getRecords().getFirst());
    }

    @Test
    void publicLookupMustUseTheDedicatedMinimalProjection() {
        User user = User.builder().id(7L).userAccount("secret-account").build();
        PublicUserSummaryVO summary = new PublicUserSummaryVO();
        summary.setId(7L);
        when(userPersistenceService.findActiveById(7L)).thenReturn(user);
        when(userViewConverter.toPublicSummary(user)).thenReturn(summary);

        PublicUserSummaryVO result = service.findActivePublicSummary(7L);

        assertSame(summary, result);
        verify(userViewConverter).toPublicSummary(user);
    }
}
