package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.model.dto.prompt.PromptReleasePublishRequest;
import com.rush.rushaicodemother.model.dto.prompt.PromptReleaseRollbackRequest;
import com.rush.rushaicodemother.model.vo.PromptReleaseMutationVO;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.prompt.PromptReleaseManagementService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptReleaseControllerTest {

    private static final String EVIDENCE_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void publishMustUseAuthenticatedAdministratorIdentity() {
        PromptReleaseManagementService service = mock(PromptReleaseManagementService.class);
        UserService userService = mock(UserService.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        PromptReleaseController controller = new PromptReleaseController(service, userService);
        PromptReleasePublishRequest request = new PromptReleasePublishRequest();
        request.setPromptKey("test-prompt");
        request.setStableVersion("v1");
        request.setCanaryVersion("v2");
        request.setCanaryPercentage(10);
        request.setExpectedRevision(3L);
        request.setChangeNote("canary release");
        request.setEvidenceId(EVIDENCE_ID);
        PromptReleaseMutationVO mutation = new PromptReleaseMutationVO(
                "test-prompt", 4L, 4L, "bundle-4", true);
        when(userService.getLoginUserId(servletRequest)).thenReturn(9L);
        when(service.publish(any(), org.mockito.ArgumentMatchers.eq(9L))).thenReturn(mutation);

        var response = controller.publish(request, servletRequest);

        assertEquals(mutation, response.getData());
        ArgumentCaptor<PromptReleaseManagementService.PublishCommand> captor =
                ArgumentCaptor.forClass(PromptReleaseManagementService.PublishCommand.class);
        verify(service).publish(captor.capture(), org.mockito.ArgumentMatchers.eq(9L));
        assertEquals(3L, captor.getValue().expectedRevision());
        assertEquals(10, captor.getValue().canaryPercentage());
        assertEquals(EVIDENCE_ID, captor.getValue().evidenceId());
    }

    @Test
    void rollbackMustUseAuthenticatedAdministratorIdentity() {
        PromptReleaseManagementService service = mock(PromptReleaseManagementService.class);
        UserService userService = mock(UserService.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        PromptReleaseController controller = new PromptReleaseController(service, userService);
        PromptReleaseRollbackRequest request = new PromptReleaseRollbackRequest();
        request.setPromptKey("test-prompt");
        request.setTargetRevision(2L);
        request.setExpectedRevision(4L);
        request.setChangeNote("restore known good");
        PromptReleaseMutationVO mutation = new PromptReleaseMutationVO(
                "test-prompt", 5L, 5L, "bundle-5", true);
        when(userService.getLoginUserId(servletRequest)).thenReturn(9L);
        when(service.rollback(any(), org.mockito.ArgumentMatchers.eq(9L))).thenReturn(mutation);

        assertEquals(mutation, controller.rollback(request, servletRequest).getData());
    }

    @Test
    void everyPromptReleaseEndpointMustRequireAdministratorRole() {
        for (Method method : PromptReleaseController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            AuthCheck authCheck = method.getAnnotation(AuthCheck.class);
            assertTrue(authCheck != null,
                    () -> "missing @AuthCheck on " + method.getName() + Arrays.toString(method.getParameterTypes()));
            assertEquals(UserConstant.ADMIN_ROLE, authCheck.mustRole());
        }
    }
}
