package com.rush.rushaicodemother.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Comparator;

/**
 * Converts framework validation exceptions into stable, client-safe response messages.
 */
@Component
public class ValidationExceptionMessageResolver {

    public String resolve(BindException exception) {
        if (!exception.getBindingResult().getFieldErrors().isEmpty()) {
            return formatFieldError(exception.getBindingResult().getFieldErrors().getFirst());
        }
        return exception.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(this::formatValidationError)
                .orElse(ErrorCode.PARAMS_ERROR.getMessage());
    }

    public String resolve(ConstraintViolationException exception) {
        return exception.getConstraintViolations().stream()
                .sorted(Comparator.comparing(this::propertyPath))
                .findFirst()
                .map(this::formatConstraintViolation)
                .orElse(ErrorCode.PARAMS_ERROR.getMessage());
    }

    public String resolve(HandlerMethodValidationException exception) {
        if (!exception.getParameterValidationResults().isEmpty()) {
            return formatParameterValidationResult(exception.getParameterValidationResults().getFirst());
        }
        return exception.getCrossParameterValidationResults().stream()
                .findFirst()
                .map(this::formatValidationError)
                .orElse(ErrorCode.PARAMS_ERROR.getMessage());
    }

    public String resolve(MethodArgumentTypeMismatchException exception) {
        String parameterName = normalize(exception.getName(), "unknown");
        return parameterError(parameterName, "type mismatch");
    }

    public String resolve(ServletRequestBindingException exception) {
        if (exception instanceof MissingServletRequestParameterException missingParameter) {
            return parameterError(missingParameter.getParameterName(), "is required");
        }
        if (exception instanceof MissingPathVariableException missingPathVariable) {
            return parameterError(missingPathVariable.getVariableName(), "is required");
        }
        return ErrorCode.PARAMS_ERROR.getMessage();
    }

    private String formatFieldError(FieldError fieldError) {
        return parameterError(fieldError.getField(),
                normalize(fieldError.getDefaultMessage(), "invalid value"));
    }

    private String formatConstraintViolation(ConstraintViolation<?> violation) {
        String propertyPath = propertyPath(violation);
        int separatorIndex = propertyPath.lastIndexOf('.');
        String parameterName = separatorIndex >= 0 ? propertyPath.substring(separatorIndex + 1) : propertyPath;
        String reason = normalize(violation.getMessage(), "invalid value");
        if (!StringUtils.hasText(parameterName)) {
            return ErrorCode.PARAMS_ERROR.getMessage() + ": " + reason;
        }
        return parameterError(parameterName, reason);
    }

    private String formatParameterValidationResult(ParameterValidationResult result) {
        String parameterName = normalize(result.getMethodParameter().getParameterName(), "argument");
        String reason = result.getResolvableErrors().stream()
                .findFirst()
                .map(this::resolveDefaultMessage)
                .orElse("invalid value");
        return parameterError(parameterName, reason);
    }

    private String formatValidationError(MessageSourceResolvable error) {
        return ErrorCode.PARAMS_ERROR.getMessage() + ": " + resolveDefaultMessage(error);
    }

    private String resolveDefaultMessage(MessageSourceResolvable error) {
        return normalize(error.getDefaultMessage(), "invalid value");
    }

    private String propertyPath(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
    }

    private String parameterError(String parameterName, String reason) {
        return ErrorCode.PARAMS_ERROR.getMessage() + ": " + parameterName + " " + reason;
    }

    private String normalize(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
