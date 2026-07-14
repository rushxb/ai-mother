package com.rush.rushaicodemother.exception;

import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import dev.langchain4j.guardrail.GuardrailException;
import dev.langchain4j.guardrail.InputGuardrailException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Central exception boundary for REST controllers.
 *
 * <p>The current client protocol represents failures through {@link BaseResponse#getCode()}.
 * Responses therefore remain HTTP 200 in this migration to avoid silently changing existing
 * Axios control flow. HTTP status semantics require a coordinated client and server migration.</p>
 */
@Hidden
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final SseExceptionResponseWriter sseExceptionResponseWriter;
    private final ValidationExceptionMessageResolver validationExceptionMessageResolver;

    @ExceptionHandler(GuardrailException.class)
    public BaseResponse<?> guardrailExceptionHandler(GuardrailException exception,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        String readableMessage = extractGuardrailMessage(exception.getMessage());
        int errorCode = exception instanceof InputGuardrailException
                ? ErrorCode.PARAMS_ERROR.getCode()
                : ErrorCode.OPERATION_ERROR.getCode();
        log.warn("Guardrail rejected request [{}]: {}", errorCode, readableMessage);
        return respond(request, response, errorCode, readableMessage);
    }

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException exception,
                                                    HttpServletRequest request,
                                                    HttpServletResponse response) {
        String safeMessage = normalizeMessage(exception.getMessage(), defaultMessageFor(exception.getCode()));
        log.warn("Business request failed [{}]: {}", exception.getCode(), safeMessage);
        return respond(request, response, exception.getCode(), safeMessage);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<?> methodArgumentNotValidExceptionHandler(MethodArgumentNotValidException exception,
                                                                  HttpServletRequest request,
                                                                  HttpServletResponse response) {
        String message = validationExceptionMessageResolver.resolve(exception);
        log.debug("Request body validation failed: {}", message);
        return respond(request, response, ErrorCode.PARAMS_ERROR.getCode(), message);
    }

    @ExceptionHandler(BindException.class)
    public BaseResponse<?> bindExceptionHandler(BindException exception,
                                                HttpServletRequest request,
                                                HttpServletResponse response) {
        String message = validationExceptionMessageResolver.resolve(exception);
        log.debug("Request parameter binding failed: {}", message);
        return respond(request, response, ErrorCode.PARAMS_ERROR.getCode(), message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public BaseResponse<?> constraintViolationExceptionHandler(ConstraintViolationException exception,
                                                               HttpServletRequest request,
                                                               HttpServletResponse response) {
        String message = validationExceptionMessageResolver.resolve(exception);
        log.debug("Controller method validation failed: {}", message);
        return respond(request, response, ErrorCode.PARAMS_ERROR.getCode(), message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public BaseResponse<?> handlerMethodValidationExceptionHandler(HandlerMethodValidationException exception,
                                                                   HttpServletRequest request,
                                                                   HttpServletResponse response) {
        String message = validationExceptionMessageResolver.resolve(exception);
        log.debug("Handler method validation failed: {}", message);
        return respond(request, response, ErrorCode.PARAMS_ERROR.getCode(), message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public BaseResponse<?> httpMessageNotReadableExceptionHandler(HttpMessageNotReadableException exception,
                                                                 HttpServletRequest request,
                                                                 HttpServletResponse response) {
        log.debug("Request body is missing or malformed [{}]", ErrorCode.PARAMS_ERROR.getCode());
        return respond(request, response,
                ErrorCode.PARAMS_ERROR.getCode(), ErrorCode.PARAMS_ERROR.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public BaseResponse<?> methodArgumentTypeMismatchExceptionHandler(MethodArgumentTypeMismatchException exception,
                                                                      HttpServletRequest request,
                                                                      HttpServletResponse response) {
        String message = validationExceptionMessageResolver.resolve(exception);
        log.debug("Request parameter type mismatch: {}", message);
        return respond(request, response, ErrorCode.PARAMS_ERROR.getCode(), message);
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public BaseResponse<?> servletRequestBindingExceptionHandler(ServletRequestBindingException exception,
                                                                 HttpServletRequest request,
                                                                 HttpServletResponse response) {
        String message = validationExceptionMessageResolver.resolve(exception);
        log.debug("Required request value is missing: {}", message);
        return respond(request, response, ErrorCode.PARAMS_ERROR.getCode(), message);
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public BaseResponse<?> serviceUnavailableExceptionHandler(ServiceUnavailableException exception,
                                                              HttpServletRequest request,
                                                              HttpServletResponse response) {
        log.warn("Request processing temporarily unavailable [{}]: {}",
                ErrorCode.SERVICE_UNAVAILABLE_ERROR.getCode(), exception.getClass().getSimpleName());
        return respond(request, response,
                ErrorCode.SERVICE_UNAVAILABLE_ERROR.getCode(), exception.getPublicMessage());
    }

    @ExceptionHandler(Exception.class)
    public BaseResponse<?> unexpectedExceptionHandler(Exception exception,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        log.error("Unexpected request processing failure", LogExceptionSanitizer.sanitize(exception));
        return respond(request, response,
                ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage());
    }


    private BaseResponse<?> respond(HttpServletRequest request,
                                    HttpServletResponse response,
                                    int errorCode,
                                    String errorMessage) {
        String safeMessage = normalizeMessage(errorMessage, defaultMessageFor(errorCode));
        if (sseExceptionResponseWriter.writeIfApplicable(request, response, errorCode, safeMessage)) {
            return null;
        }
        return ResultUtils.error(errorCode, safeMessage);
    }

    private String extractGuardrailMessage(String rawMessage) {
        if (!StringUtils.hasText(rawMessage)) {
            return "Request rejected by the safety policy";
        }
        String marker = "failed with this message:";
        int markerIndex = rawMessage.indexOf(marker);
        if (markerIndex >= 0) {
            String readableMessage = rawMessage.substring(markerIndex + marker.length()).trim();
            if (StringUtils.hasText(readableMessage)) {
                return readableMessage;
            }
        }
        return rawMessage.trim();
    }

    private String defaultMessageFor(int errorCode) {
        for (ErrorCode candidate : ErrorCode.values()) {
            if (candidate.getCode() == errorCode) {
                return candidate.getMessage();
            }
        }
        return ErrorCode.OPERATION_ERROR.getMessage();
    }

    private String normalizeMessage(String message, String fallback) {
        return StringUtils.hasText(message) ? message.trim() : fallback;
    }

}
