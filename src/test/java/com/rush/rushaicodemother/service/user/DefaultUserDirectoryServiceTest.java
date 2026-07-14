package com.rush.rushaicodemother.service.user;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.model.dto.user.UserQueryRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.UserVO;
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
        UserVO firstView = new UserVO();
        firstView.setId(1L);
        when(userPersistenceService.findActiveByIds(Arrays.asList(1L, null)))
                .thenReturn(Arrays.asList(firstUser, duplicateUser, invalidUser, null));
        when(userViewConverter.toUserView(firstUser)).thenReturn(firstView);

        Map<Long, UserVO> result = service.findActiveUserViews(Arrays.asList(1L, null));

        assertEquals(Map.of(1L, firstView), result);
        verify(userViewConverter).toUserView(firstUser);
    }

    @Test
    void pageLookupMustReturnVoPageWithoutExposingEntities() {
        UserQueryRequest request = new UserQueryRequest();
        User user = User.builder().id(7L).build();
        UserVO userView = new UserVO();
        userView.setId(7L);
        Page<User> userPage = new Page<>(2, 5, 11);
        userPage.setRecords(List.of(user));
        when(userPersistenceService.pageActiveUsers(request)).thenReturn(userPage);
        when(userViewConverter.toUserView(user)).thenReturn(userView);

        Page<UserVO> result = service.pageActiveUserViews(request);

        assertEquals(2, result.getPageNumber());
        assertEquals(5, result.getPageSize());
        assertEquals(11, result.getTotalRow());
        assertSame(userView, result.getRecords().getFirst());
    }
}
