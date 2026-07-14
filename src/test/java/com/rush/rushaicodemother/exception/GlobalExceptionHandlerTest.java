package com.rush.rushaicodemother.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.infrastructure.redis.ChatMemoryFallbackCapacityExceededException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.LoggerFactory;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void shouldNotLogMalformedRequestExceptionDetails() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Level originalLevel = logger.getLevel();
        boolean originalAdditive = logger.isAdditive();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            exceptionHandler.httpMessageNotReadableExceptionHandler(
                    new HttpMessageNotReadableException(
                            "request-api-key=secret", new MockHttpInputMessage(new byte[0])),
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse());
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(originalAdditive);
            logger.setLevel(originalLevel);
            appender.stop();
        }

        ILoggingEvent event = appender.list.stream()
                .filter(candidate -> candidate.getFormattedMessage()
                        .contains("Request body is missing or malformed"))
                .findFirst()
                .orElseThrow();
        assertFalse(event.getFormattedMessage().contains("request-api-key"));
        assertFalse(event.getFormattedMessage().contains("secret"));
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
    void shouldHideUnexpectedExceptionDetailsFromResponseAndLogs() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        boolean originalAdditive = logger.isAdditive();
        appender.start();
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            mockMvc.perform(get("/test/unexpected"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ErrorCode.SYSTEM_ERROR.getCode()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.SYSTEM_ERROR.getMessage()))
                    .andExpect(content().string(not(containsString("database-password"))));
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(originalAdditive);
            appender.stop();
        }

        ILoggingEvent event = appender.list.stream()
                .filter(candidate -> candidate.getFormattedMessage()
                        .contains("Unexpected request processing failure"))
                .findFirst()
                .orElseThrow();
        assertFalse(event.getFormattedMessage().contains("database-password"));
        assertNotNull(event.getThrowableProxy());
        assertFalse(event.getThrowableProxy().getMessage().contains("database-password"));
        assertTrue(event.getThrowableProxy().getMessage().contains("java.lang.IllegalStateException"));
    }

    @Test
    void shouldClassifyChatMemoryFallbackCapacityAsTemporarilyUnavailable() throws Exception {
        mockMvc.perform(get("/test/chat-memory-capacity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SERVICE_UNAVAILABLE_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.SERVICE_UNAVAILABLE_ERROR.getMessage()))
                .andExpect(content().string(not(containsString("fallback-max-entries"))))
                .andExpect(content().string(not(containsString("128"))));
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

        @GetMapping("/test/chat-memory-capacity")
        BaseResponse<Void> chatMemoryCapacityExceeded() {
            throw new ChatMemoryFallbackCapacityExceededException(128);
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
