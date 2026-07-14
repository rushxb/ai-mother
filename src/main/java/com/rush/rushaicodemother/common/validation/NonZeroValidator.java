package com.rush.rushaicodemother.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.math.BigDecimal;

/** Validates {@link NonZero} for any standard {@link Number} implementation. */
public final class NonZeroValidator implements ConstraintValidator<NonZero, Number> {

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
