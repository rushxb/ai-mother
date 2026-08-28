package com.rush.rushaicodemother.orchestration.governance.access;

import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditResource;

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

    /** 脱敏审计中的受控资源类型。 */
    GenerationControlAuditResource auditResource();

    /**
     * 从 Controller 参数取得资源编号的受信 SpEL。
     *
     * <p>只允许使用代码中的常量表达式，不接收用户提供的表达式。
     * 参数按 {@code #p0}、{@code #p1} 索引引用。</p>
     */
    String auditResourceId();
}
