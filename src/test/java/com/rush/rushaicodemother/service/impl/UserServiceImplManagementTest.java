package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.dto.user.UserAddRequest;
import com.rush.rushaicodemother.model.dto.user.UserUpdateRequest;
import com.rush.rushaicodemother.security.password.PasswordHashService;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.user.UserPersistenceService;
import com.rush.rushaicodemother.service.user.UserViewConverter;
import com.rush.rushaicodemother.service.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserServiceImplManagementTest {

    private PasswordHashService passwordHashService;
    private UserCreditService userCreditService;
    private UserPersistenceService userPersistenceService;
    private TenantProvisioningService tenantProvisioningService;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        passwordHashService = mock(PasswordHashService.class);
        userCreditService = mock(UserCreditService.class);
        userPersistenceService = mock(UserPersistenceService.class);
        tenantProvisioningService = mock(TenantProvisioningService.class);
        userService = new UserServiceImpl(
                passwordHashService,
                userCreditService,
                userPersistenceService,
                mock(UserViewConverter.class),
                tenantProvisioningService
        );
    }

    @Test
    void adminCreateMustPersistExplicitFieldsAndInitializeCredit() {
        UserAddRequest request = createRequest(25L);
        when(passwordHashService.hash("secure-password")).thenReturn("password-hash");
        when(userPersistenceService.createUser(org.mockito.ArgumentMatchers.any())).thenReturn(101L);

        long userId = userService.createUser(request, 9L);

        assertEquals(101L, userId);
        ArgumentCaptor<UserPersistenceService.NewUser> userCaptor =
                ArgumentCaptor.forClass(UserPersistenceService.NewUser.class);
        verify(userPersistenceService).createUser(userCaptor.capture());
        verify(tenantProvisioningService).ensurePersonalTenant(101L, "New User");
        UserPersistenceService.NewUser newUser = userCaptor.getValue();
        assertEquals("new-user", newUser.userAccount());
        assertEquals("password-hash", newUser.passwordHash());
        assertEquals("New User", newUser.userName());
        assertEquals("user", newUser.userRole());
        assertEquals(0L, newUser.creditBalance());
        verify(userCreditService).initializeCredit(101L, 25L, 9L);
    }

    @Test
    void adminCreateWithZeroCreditMustNotCreateMeaninglessLedgerEntry() {
        UserAddRequest request = createRequest(0L);
        when(passwordHashService.hash("secure-password")).thenReturn("password-hash");
        when(userPersistenceService.createUser(org.mockito.ArgumentMatchers.any())).thenReturn(102L);

        assertEquals(102L, userService.createUser(request, 9L));

        verify(tenantProvisioningService).ensurePersonalTenant(102L, "New User");
        verifyNoInteractions(userCreditService);
    }

    @Test
    void adminUpdateMustDelegateOnlyEditableFields() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setId(7L);
        request.setUserName("updated-name");
        request.setUserAvatar("updated-avatar");
        request.setUserProfile("updated-profile");
        request.setUserRole("admin");

        userService.updateUser(request);

        verify(userPersistenceService).updateAdministrationFields(
                7L,
                "updated-name",
                "updated-avatar",
                "updated-profile",
                "admin"
        );
    }

    @Test
    void adminUpdateMustPropagateMissingUserFailureFromPersistenceBoundary() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setId(404L);
        request.setUserName("missing-user");
        BusinessException expected = new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在或已删除");
        org.mockito.Mockito.doThrow(expected)
                .when(userPersistenceService)
                .updateAdministrationFields(404L, "missing-user", null, null, null);

        BusinessException actual = assertThrows(BusinessException.class, () -> userService.updateUser(request));

        assertEquals(expected, actual);
    }

    @Test
    void adminDeleteMustDelegateToLogicalDeleteBoundary() {
        userService.deleteUser(7L);

        verify(userPersistenceService).logicallyDelete(7L);
    }

    @Test
    void adminCreateMustRejectMissingOperatorAtServiceBoundary() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userService.createUser(createRequest(10L), null)
        );

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        verify(userPersistenceService, never()).createUser(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(passwordHashService, userCreditService);
    }

    private UserAddRequest createRequest(Long creditBalance) {
        UserAddRequest request = new UserAddRequest();
        request.setUserAccount("new-user");
        request.setUserPassword("secure-password");
        request.setUserName("New User");
        request.setUserRole(null);
        request.setCreditBalance(creditBalance);
        return request;
    }
}
