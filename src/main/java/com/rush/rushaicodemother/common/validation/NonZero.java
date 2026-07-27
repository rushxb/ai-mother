package com.rush.rushaicodemother.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 验证数值不为零。
 *
 * <p>{@code null} 被故意认为是有效的，因此调用者可以用以下方式组成此约束
 * {@code @NotNull} 当该值是强制的时，遵循 Bean 验证约定。</p>
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
