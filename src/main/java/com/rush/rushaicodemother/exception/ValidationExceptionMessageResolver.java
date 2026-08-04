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
 * 将框架验证异常转换为稳定的、客户端安全的响应消息。
 */
@Component
public class ValidationExceptionMessageResolver {

    private static final String UNKNOWN_PARAMETER = "未知参数";
    private static final String ARGUMENT_PARAMETER = "参数";
    private static final String INVALID_VALUE_REASON = "参数值无效";
    private static final String TYPE_MISMATCH_REASON = "类型不匹配";
    private static final String REQUIRED_REASON = "不能为空";

    private final UserFacingMessageResolver userFacingMessageResolver;

    public ValidationExceptionMessageResolver(UserFacingMessageResolver userFacingMessageResolver) {
        this.userFacingMessageResolver = userFacingMessageResolver;
    }

    /**
 * 根据当前上下文解析校验异常消息。
 *
 * @param exception 待转换或处理的异常
 * @return 处理后的校验异常消息文本
 */
    public String resolve(BindException exception) {
        if (!exception.getBindingResult().getFieldErrors().isEmpty()) {
            return formatFieldError(exception.getBindingResult().getFieldErrors().getFirst());
        }
        return exception.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(this::formatValidationError)
                .orElse(ErrorCode.PARAMS_ERROR.getMessage());
    }

    /**
 * 根据当前上下文解析校验异常消息。
 *
 * @param exception 待转换或处理的异常
 * @return 处理后的校验异常消息文本
 */
    public String resolve(ConstraintViolationException exception) {
        return exception.getConstraintViolations().stream()
                .sorted(Comparator.comparing(this::propertyPath))
                .findFirst()
                .map(this::formatConstraintViolation)
                .orElse(ErrorCode.PARAMS_ERROR.getMessage());
    }

    /**
 * 根据当前上下文解析校验异常消息。
 *
 * @param exception 待转换或处理的异常
 * @return 处理后的校验异常消息文本
 */
    public String resolve(HandlerMethodValidationException exception) {
        if (!exception.getParameterValidationResults().isEmpty()) {
            return formatParameterValidationResult(exception.getParameterValidationResults().getFirst());
        }
        return exception.getCrossParameterValidationResults().stream()
                .findFirst()
                .map(this::formatValidationError)
                .orElse(ErrorCode.PARAMS_ERROR.getMessage());
    }

    /**
 * 根据当前上下文解析校验异常消息。
 *
 * @param exception 待转换或处理的异常
 * @return 处理后的校验异常消息文本
 */
    public String resolve(MethodArgumentTypeMismatchException exception) {
        String parameterName = normalize(exception.getName(), UNKNOWN_PARAMETER);
        return parameterError(parameterName, TYPE_MISMATCH_REASON);
    }

    /**
 * 根据当前上下文解析校验异常消息。
 *
 * @param exception 待转换或处理的异常
 * @return 处理后的校验异常消息文本
 */
    public String resolve(ServletRequestBindingException exception) {
        if (exception instanceof MissingServletRequestParameterException missingParameter) {
            return parameterError(missingParameter.getParameterName(), REQUIRED_REASON);
        }
        if (exception instanceof MissingPathVariableException missingPathVariable) {
            return parameterError(missingPathVariable.getVariableName(), REQUIRED_REASON);
        }
        return ErrorCode.PARAMS_ERROR.getMessage();
    }

    private String formatFieldError(FieldError fieldError) {
        return parameterError(fieldError.getField(),
                normalizeReason(fieldError.getDefaultMessage()));
    }

    private String formatConstraintViolation(ConstraintViolation<?> violation) {
        String propertyPath = propertyPath(violation);
        int separatorIndex = propertyPath.lastIndexOf('.');
        String parameterName = separatorIndex >= 0 ? propertyPath.substring(separatorIndex + 1) : propertyPath;
        String reason = normalizeReason(violation.getMessage());
        if (!StringUtils.hasText(parameterName)) {
            return ErrorCode.PARAMS_ERROR.getMessage() + ": " + reason;
        }
        return parameterError(parameterName, reason);
    }

    private String formatParameterValidationResult(ParameterValidationResult result) {
        String parameterName = normalize(result.getMethodParameter().getParameterName(), ARGUMENT_PARAMETER);
        String reason = result.getResolvableErrors().stream()
                .findFirst()
                .map(this::resolveDefaultMessage)
                .orElse(INVALID_VALUE_REASON);
        return parameterError(parameterName, reason);
    }

    private String formatValidationError(MessageSourceResolvable error) {
        return ErrorCode.PARAMS_ERROR.getMessage() + ": " + resolveDefaultMessage(error);
    }

    private String resolveDefaultMessage(MessageSourceResolvable error) {
        return normalizeReason(error.getDefaultMessage());
    }

    private String propertyPath(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
    }

    private String parameterError(String parameterName, String reason) {
        return ErrorCode.PARAMS_ERROR.getMessage() + ": " + parameterName + " " + reason;
    }

    /**
     * 校验框架的默认消息可能随依赖或区域设置变为英文；用户边界统一降级为稳定中文。
     */
    private String normalizeReason(String reason) {
        String normalized = normalize(reason, INVALID_VALUE_REASON);
        return userFacingMessageResolver.containsChinese(normalized) ? normalized : INVALID_VALUE_REASON;
    }

    private String normalize(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
