package com.rush.rushaicodemother.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a numeric value is not zero.
 *
 * <p>{@code null} is intentionally considered valid so callers can compose this constraint with
 * {@code @NotNull} when the value is mandatory, following Bean Validation conventions.</p>
 */
@Documented
@Constraint(validatedBy = NonZeroValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NonZero {

    String message() default "不能为 0";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
