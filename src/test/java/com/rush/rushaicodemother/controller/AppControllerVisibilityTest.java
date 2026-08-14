package com.rush.rushaicodemother.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.application.app.AppManagementApplicationService;
import com.rush.rushaicodemother.application.app.AppQueryApplicationService;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.GlobalExceptionHandler;
import com.rush.rushaicodemother.exception.SseExceptionResponseWriter;
import com.rush.rushaicodemother.exception.UserFacingMessageResolver;
import com.rush.rushaicodemother.exception.ValidationExceptionMessageResolver;
import com.rush.rushaicodemother.model.dto.app.AppQueryRequest;
import com.rush.rushaicodemother.model.vo.PublicAppVO;
import com.rush.rushaicodemother.model.vo.PublicUserSummaryVO;
import com.rush.rushaicodemother.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AppControllerVisibilityTest {

    private AppQueryApplicationService queryService;
    private UserService userService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(AppQueryApplicationService.class);
        userService = mock(UserService.class);
        AppController controller = new AppController(
                mock(AppManagementApplicationService.class),
                queryService,
                userService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(
                        new SseExceptionResponseWriter(new ObjectMapper()),
                        new ValidationExceptionMessageResolver(new UserFacingMessageResolver()),
                        new UserFacingMessageResolver()))
                .build();
    }

    @Test
    void anonymousDetailLookupMustFailBeforeApplicationIdIsQueried() throws Exception {
        when(userService.getLoginUser(any()))
                .thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        mockMvc.perform(get("/app/get/vo").param("id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()));

        verifyNoInteractions(queryService);
    }

    @Test
    void featuredAppsMustNotExposePromptRuntimeDatabaseOrAdminUserFields() throws Exception {
        PublicAppVO app = new PublicAppVO();
        app.setId(7L);
        app.setAppName("Featured app");
        PublicUserSummaryVO user = new PublicUserSummaryVO();
        user.setId(9L);
        user.setUserName("Alice");
        app.setUser(user);
        Page<PublicAppVO> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(app));
        when(queryService.listFeatured(any(AppQueryRequest.class))).thenReturn(page);

        mockMvc.perform(post("/app/good/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pageNum":1,"pageSize":10}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(7L))
                .andExpect(jsonPath("$.data.records[0].appName").value("Featured app"))
                .andExpect(jsonPath("$.data.records[0].initPrompt").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].generatingMessage").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].devServerPort").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].databaseResource").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].user.userRole").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].user.creditBalance").doesNotExist());
    }
}
