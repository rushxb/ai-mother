package com.rush.rushaicodemother.orchestration.governance.access;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将 HTTP 控制入口绑定到权威权限矩阵。
 *
 * <p>该注解提供可执行的架构元数据；租户资源仍由服务层根据真实 app/task 归属校验，
 * 平台入口同时必须使用 {@code AuthCheck} 的管理员切面。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GenerationControlAccess {

    GenerationControlPermission value();
}
