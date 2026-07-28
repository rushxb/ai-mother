package com.rush.rushaicodemother.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.math.BigDecimal;

/** 验证任何标准 {@link Number} 实现的 {@link NonZero}。 */
public final class NonZeroValidator implements ConstraintValidator<NonZero, Number> {

    /**
 * 判断目标值是否有效。
 *
 * @param value 待处理值
 * @param context 执行上下文
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean isValid(Number value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        try {
            return new BigDecimal(value.toString()).compareTo(BigDecimal.ZERO) != 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
