package com.rush.rushaicodemother.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 将故障样本身份绑定到实际执行生产接口的测试方法。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GenerationFailureEvidence {

    String value();
}
