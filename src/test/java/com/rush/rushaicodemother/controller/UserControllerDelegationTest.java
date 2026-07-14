package com.rush.rushaicodemother.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.GlobalExceptionHandler;
import com.rush.rushaicodemother.exception.SseExceptionResponseWriter;
import com.rush.rushaicodemother.exception.ValidationExceptionMessageResolver;
import com.rush.rushaicodemother.model.dto.user.UserQueryRequest;
import com.rush.rushaicodemother.model.vo.UserVO;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.credit.AdminCreditAdjustmentCommand;
import com.rush.rushaicodemother.service.user.UserDirectoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerDelegationTest {

    private UserDirectoryService userDirectoryService;
    private UserService userService;
    private UserCreditService userCreditService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userDirectoryService = mock(UserDirectoryService.class);
        userService = mock(UserService.class);
        userCreditService = mock(UserCreditService.class);
        UserController controller = new UserController(
                userService,
                userCreditService,
                userDirectoryService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(
                        new SseExceptionResponseWriter(new ObjectMapper()),
                        new ValidationExceptionMessageResolver()
                ))
                .build();
    }

    @Test
    void adminUserLookupMustDelegateToReadOnlyDirectory() throws Exception {
        UserVO userView = userView(7L, "Alice");
        when(userDirectoryService.findActiveUserView(7L)).thenReturn(userView);

        mockMvc.perform(get("/user/get").param("id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.id").value(7L))
                .andExpect(jsonPath("$.data.userName").value("Alice"));

        verify(userDirectoryService).findActiveUserView(7L);
    }

    @Test
    void publicUserViewLookupMustReturnNotFoundForMissingActiveUser() throws Exception {
        when(userDirectoryService.findActiveUserView(404L)).thenReturn(null);

        mockMvc.perform(get("/user/get/vo").param("id", "404"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND_ERROR.getCode()));

        verify(userDirectoryService).findActiveUserView(404L);
    }

    @Test
    void userPageLookupMustDelegateToReadOnlyDirectory() throws Exception {
        UserVO userView = userView(9L, "Bob");
        Page<UserVO> page = new Page<>(2, 5, 1);
        page.setRecords(List.of(userView));
        when(userDirectoryService.pageActiveUserViews(any(UserQueryRequest.class)))
                .thenReturn(page);

        mockMvc.perform(post("/user/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pageNum": 2,
                                  "pageSize": 5,
                                  "userName": "Bob"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.records[0].id").value(9L))
                .andExpect(jsonPath("$.data.records[0].userName").value("Bob"));

        ArgumentCaptor<UserQueryRequest> requestCaptor = ArgumentCaptor.forClass(UserQueryRequest.class);
        verify(userDirectoryService).pageActiveUserViews(requestCaptor.capture());
        UserQueryRequest delegatedRequest = requestCaptor.getValue();
        assertEquals(2, delegatedRequest.getPageNum());
        assertEquals(5, delegatedRequest.getPageSize());
        assertEquals("Bob", delegatedRequest.getUserName());
    }

    @Test
    void creditAdjustmentMustDelegateIdempotentCommand() throws Exception {
        when(userService.getLoginUserId(any())).thenReturn(9L);

        mockMvc.perform(post("/user/credit/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "550e8400-e29b-41d4-a716-446655440000",
                                  "userId": 7,
                                  "changeAmount": -2,
                                  "remark": "manual correction"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.getCode()));

        ArgumentCaptor<AdminCreditAdjustmentCommand> commandCaptor =
                ArgumentCaptor.forClass(AdminCreditAdjustmentCommand.class);
        verify(userCreditService).adjustCreditByAdmin(commandCaptor.capture());
        AdminCreditAdjustmentCommand command = commandCaptor.getValue();
        assertEquals("550e8400-e29b-41d4-a716-446655440000", command.requestId());
        assertEquals(7L, command.userId());
        assertEquals(-2L, command.changeAmount());
        assertEquals("manual correction", command.remark());
        assertEquals(9L, command.adminUserId());
    }

    private UserVO userView(long id, String userName) {
        UserVO userView = new UserVO();
        userView.setId(id);
        userView.setUserName(userName);
        return userView;
    }
}
