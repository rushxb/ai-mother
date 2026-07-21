package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.DeleteRequest;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelAddRequest;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelToggleRequest;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelUpdateRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.aimodel.AiModelManagementService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelControllerDelegationTest {

    private static final String EVIDENCE_ID = "550e8400-e29b-41d4-a716-446655440000";

    private AiModelManagementService managementService;
    private UserService userService;
    private AiModelController controller;

    @BeforeEach
    void setUp() {
        managementService = mock(AiModelManagementService.class);
        userService = mock(UserService.class);
        controller = new AiModelController(managementService, userService);
    }

    @Test
    void addMustMapRequestToCommandWithoutBuildingPersistenceEntity() {
        AiModelAddRequest request = createRequest();
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(userService.getLoginUser(servletRequest)).thenReturn(User.builder().id(9L).build());
        when(managementService.createModel(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(9L))).thenReturn(101L);

        BaseResponse<Long> response = controller.addModel(request, servletRequest);

        assertEquals(101L, response.getData());
        ArgumentCaptor<AiModelManagementService.CreateCommand> commandCaptor =
                ArgumentCaptor.forClass(AiModelManagementService.CreateCommand.class);
        verify(managementService).createModel(commandCaptor.capture(), org.mockito.ArgumentMatchers.eq(9L));
        assertEquals("custom", commandCaptor.getValue().provider());
        assertEquals("secret", commandCaptor.getValue().apiKey());
    }

    @Test
    void updateMustMapOnlyManagementCommand() {
        AiModelUpdateRequest request = new AiModelUpdateRequest();
        request.setId(7L);
        request.setModelName("Updated");

        BaseResponse<Boolean> response = controller.updateModel(request);

        assertTrue(response.getData());
        ArgumentCaptor<AiModelManagementService.UpdateCommand> commandCaptor =
                ArgumentCaptor.forClass(AiModelManagementService.UpdateCommand.class);
        verify(managementService).updateModel(commandCaptor.capture());
        assertEquals(7L, commandCaptor.getValue().id());
        assertEquals("Updated", commandCaptor.getValue().modelName());
    }

    @Test
    void deleteMustDelegateAndOnlyReportSuccessAfterServiceReturns() {
        DeleteRequest request = new DeleteRequest();
        request.setId(7L);

        BaseResponse<Boolean> response = controller.deleteModel(request);

        assertTrue(response.getData());
        verify(managementService).deleteModel(7L);
    }

    @Test
    void toggleMustForwardAuthenticatedOperatorAndEvidenceReference() {
        AiModelToggleRequest request = new AiModelToggleRequest();
        request.setId(7L);
        request.setEvidenceId(EVIDENCE_ID);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(userService.getLoginUser(servletRequest)).thenReturn(User.builder().id(9L).build());

        controller.toggleModelEnabled(request, servletRequest);

        verify(managementService).toggleModelEnabled(7L, EVIDENCE_ID, 9L);
    }

    private AiModelAddRequest createRequest() {
        AiModelAddRequest request = new AiModelAddRequest();
        request.setModelName("Model");
        request.setProvider("custom");
        request.setModelId("model-id");
        request.setBaseUrl("http://localhost:11434/v1");
        request.setApiKey("secret");
        request.setModelType("chat");
        return request;
    }
}
