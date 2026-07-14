package com.rush.rushaicodemother.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.GlobalExceptionHandler;
import com.rush.rushaicodemother.exception.SseExceptionResponseWriter;
import com.rush.rushaicodemother.exception.ValidationExceptionMessageResolver;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.aimodel.AiModelManagementService;
import com.rush.rushaicodemother.service.user.UserDirectoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControllerRequestValidationTest {

    @Test
    void adminUserCreationMustDelegateAtomicUseCaseToUserService() throws Exception {
        UserService userService = mock(UserService.class);
        UserCreditService userCreditService = mock(UserCreditService.class);
        when(userService.getLoginUserId(any())).thenReturn(9L);
        when(userService.createUser(any(), eq(9L))).thenReturn(101L);
        MockMvc mockMvc = createMockMvc(new UserController(
                userService,
                userCreditService,
                mock(UserDirectoryService.class)
        ));

        mockMvc.perform(post("/user/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userAccount": "new-user",
                                  "userPassword": "secure-password",
                                  "creditBalance": 25
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data").value(101L));

        verify(userService).getLoginUserId(any());
        verify(userService).createUser(any(), eq(9L));
        verifyNoInteractions(userCreditService);
    }

    @Test
    void nullAiModelIdMustBeRejectedBeforeServiceInvocation() throws Exception {
        AiModelManagementService aiModelService = mock(AiModelManagementService.class);
        AiModelController controller = new AiModelController(
                aiModelService,
                mock(UserService.class)
        );
        MockMvc mockMvc = createMockMvc(controller);

        mockMvc.perform(post("/ai-model/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()))
                .andExpect(jsonPath("$.message", containsString("id")));

        verifyNoInteractions(aiModelService);
    }

    @Test
    void nullUserIdMustBeRejectedBeforeDeleteServiceInvocation() throws Exception {
        UserService userService = mock(UserService.class);
        UserCreditService userCreditService = mock(UserCreditService.class);
        MockMvc mockMvc = createMockMvc(new UserController(
                userService,
                userCreditService,
                mock(UserDirectoryService.class)
        ));

        mockMvc.perform(post("/user/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()))
                .andExpect(jsonPath("$.message", containsString("id")));

        verifyNoInteractions(userService, userCreditService);
    }

    @ParameterizedTest
    @MethodSource("invalidCreditAdjustmentBodies")
    void invalidCreditAdjustmentMustBeRejectedBeforeServiceInvocation(String requestBody) throws Exception {
        UserService userService = mock(UserService.class);
        UserCreditService userCreditService = mock(UserCreditService.class);
        MockMvc mockMvc = createMockMvc(new UserController(
                userService,
                userCreditService,
                mock(UserDirectoryService.class)
        ));

        mockMvc.perform(post("/user/credit/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()));

        verifyNoInteractions(userService, userCreditService);
    }

    private static Stream<String> invalidCreditAdjustmentBodies() {
        String validRequestId = "550e8400-e29b-41d4-a716-446655440000";
        return Stream.of(
                "{\"userId\":1,\"changeAmount\":1,\"remark\":\"valid\"}",
                "{\"requestId\":\"not-a-uuid\",\"userId\":1,\"changeAmount\":1,\"remark\":\"valid\"}",
                "{\"requestId\":\"" + validRequestId + "\",\"userId\":null,\"changeAmount\":1,\"remark\":\"valid\"}",
                "{\"requestId\":\"" + validRequestId + "\",\"userId\":1,\"changeAmount\":0,\"remark\":\"valid\"}",
                "{\"requestId\":\"" + validRequestId + "\",\"userId\":1,\"changeAmount\":1,\"remark\":\"\"}",
                "{\"requestId\":\"" + validRequestId + "\",\"userId\":1,\"changeAmount\":1,\"remark\":\""
                        + "a".repeat(513) + "\"}"
        );
    }

    private MockMvc createMockMvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(
                        new SseExceptionResponseWriter(new ObjectMapper()),
                        new ValidationExceptionMessageResolver()
                ))
                .build();
    }
}
