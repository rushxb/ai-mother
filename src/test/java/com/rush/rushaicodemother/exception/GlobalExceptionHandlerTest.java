package com.rush.rushaicodemother.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import dev.langchain4j.guardrail.InputGuardrailException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;
    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler(
                new SseExceptionResponseWriter(new ObjectMapper()),
                new ValidationExceptionMessageResolver());
        mockMvc = MockMvcBuilders.standaloneSetup(new ExceptionTestController())
                .setControllerAdvice(exceptionHandler)
                .build();
    }

    @Test
    void shouldReturnUnifiedResponseWhenRequestBodyValidationFails() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message", containsString("name")));
    }

    @Test
    void shouldReturnUnifiedResponseWhenJsonIsMalformed() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.PARAMS_ERROR.getMessage()));
    }

    @Test
    void shouldReturnUnifiedResponseWhenParameterTypeIsInvalid() throws Exception {
        mockMvc.perform(get("/test/number").param("id", "not-a-number"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()))
                .andExpect(jsonPath("$.message", containsString("id")));
    }

    @Test
    void shouldReturnUnifiedResponseWhenRequiredParameterIsMissing() throws Exception {
        mockMvc.perform(get("/test/number"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()))
                .andExpect(jsonPath("$.message", containsString("id")));
    }

    @Test
    void shouldReturnUnifiedResponseWhenMethodValidationFails() throws Exception {
        mockMvc.perform(get("/test/number").param("id", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()))
                .andExpect(jsonPath("$.message", containsString("id")));
    }

    @Test
    void shouldPreserveBusinessErrorCodeAndSafeMessage() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_AUTH_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("Access denied for this operation"));
    }

    @Test
    void shouldHideInternalMessageForUnexpectedException() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SYSTEM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.SYSTEM_ERROR.getMessage()))
                .andExpect(content().string(not(containsString("database-password"))));
    }

    @Test
    void shouldWriteNullSafeBusinessErrorForSseRequest() throws Exception {
        mockMvc.perform(get("/app/chat/gen/code")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString(MediaType.TEXT_EVENT_STREAM_VALUE)))
                .andExpect(content().string(containsString("event: business-error")))
                .andExpect(content().string(containsString("\"code\":40000")))
                .andExpect(content().string(containsString(ErrorCode.PARAMS_ERROR.getMessage())))
                .andExpect(content().string(containsString("event: done")));
    }

    @Test
    void shouldExtractReadableGuardrailMessageThroughPublicHandler() {
        InputGuardrailException exception = new InputGuardrailException(
                "PromptSafetyGuardrail failed with this message: Prompt exceeds the allowed length");

        BaseResponse<?> response = exceptionHandler.guardrailExceptionHandler(
                exception, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), response.getCode());
        assertEquals("Prompt exceeds the allowed length", response.getMessage());
    }

    @Test
    void shouldNormalizeConstraintViolationToUnifiedResponse() {
        ValidationRequest requestBody = new ValidationRequest();
        ConstraintViolationException exception;
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            exception = new ConstraintViolationException(
                    validatorFactory.getValidator().validate(requestBody));
        }

        BaseResponse<?> response = exceptionHandler.constraintViolationExceptionHandler(
                exception, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), response.getCode());
        assertTrue(response.getMessage().contains("name"));
    }

    @RestController
    private static class ExceptionTestController {

        @PostMapping("/test/validation")
        BaseResponse<Void> validate(@Valid @RequestBody ValidationRequest request) {
            return ResultUtils.success(null);
        }

        @GetMapping("/test/number")
        BaseResponse<Long> number(@RequestParam @Positive Long id) {
            return ResultUtils.success(id);
        }

        @GetMapping("/test/business")
        BaseResponse<Void> businessError() {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "Access denied for this operation");
        }

        @GetMapping("/test/unexpected")
        BaseResponse<Void> unexpectedError() {
            throw new IllegalStateException("database-password=secret");
        }

        @GetMapping(value = "/app/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        BaseResponse<Void> sseBusinessError() {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, null);
        }
    }

    private static class ValidationRequest {

        @NotBlank
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
