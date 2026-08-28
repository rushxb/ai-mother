package com.rush.rushaicodemother.orchestration.governance.access;

import com.rush.rushaicodemother.model.enums.TenantRole;

import java.util.Objects;

/**
 * 生成控制面的权威 RBAC 与所有权矩阵。
 *
 * <p>HTTP 注解、租户角色校验和任务审批都消费这里的同一份事实。自动恢复属于系统职责，
 * 不得通过添加普通 Controller 入口把内部能力升级成用户权限。</p>
 */
public enum GenerationControlPermission {

    TASK_SUBMIT(ResourceScope.APP, TenantRole.DEVELOPER,
            ActorRule.TENANT_ROLE, Exposure.TENANT_HTTP),
    TASK_QUERY(ResourceScope.TASK, TenantRole.VIEWER,
            ActorRule.TENANT_ROLE, Exposure.TENANT_HTTP),
    TASK_CANCEL(ResourceScope.TASK, TenantRole.DEVELOPER,
            ActorRule.TENANT_ROLE, Exposure.TENANT_HTTP),
    TOOL_APPROVAL(ResourceScope.TASK, TenantRole.DEVELOPER,
            ActorRule.TASK_SUBMITTER_WITH_TENANT_ROLE, Exposure.TENANT_HTTP),
    TASK_RECOVERY(ResourceScope.SYSTEM, null,
            ActorRule.INTERNAL_SYSTEM, Exposure.INTERNAL_ONLY),
    TERMINAL_EFFECT_REPLAY(ResourceScope.PLATFORM, null,
            ActorRule.PLATFORM_ADMINISTRATOR, Exposure.PLATFORM_ADMIN_HTTP),
    BENCHMARK_MANAGE(ResourceScope.PLATFORM, null,
            ActorRule.PLATFORM_ADMINISTRATOR, Exposure.PLATFORM_ADMIN_HTTP),
    MODEL_CONFIGURE(ResourceScope.PLATFORM, null,
            ActorRule.PLATFORM_ADMINISTRATOR, Exposure.PLATFORM_ADMIN_HTTP),
    APP_DELETE(ResourceScope.APP, TenantRole.ADMIN,
            ActorRule.TENANT_ROLE_OR_PLATFORM_ADMINISTRATOR, Exposure.TENANT_HTTP);

    private final ResourceScope resourceScope;
    private final TenantRole minimumTenantRole;
    private final ActorRule actorRule;
    private final Exposure exposure;

    GenerationControlPermission(ResourceScope resourceScope,
                                TenantRole minimumTenantRole,
                                ActorRule actorRule,
                                Exposure exposure) {
        this.resourceScope = Objects.requireNonNull(resourceScope, "控制资源范围不能为空");
        this.minimumTenantRole = minimumTenantRole;
        this.actorRule = Objects.requireNonNull(actorRule, "控制主体规则不能为空");
        this.exposure = Objects.requireNonNull(exposure, "控制暴露方式不能为空");
        validateDefinition();
    }

    public ResourceScope resourceScope() {
        return resourceScope;
    }

    public TenantRole minimumTenantRole() {
        return minimumTenantRole;
    }

    public ActorRule actorRule() {
        return actorRule;
    }

    public Exposure exposure() {
        return exposure;
    }

    public boolean allowsPlatformAdministratorBypass() {
        return actorRule == ActorRule.TENANT_ROLE_OR_PLATFORM_ADMINISTRATOR;
    }

    private void validateDefinition() {
        boolean tenantScoped = resourceScope == ResourceScope.APP || resourceScope == ResourceScope.TASK;
        if (tenantScoped != (minimumTenantRole != null)) {
            throw new IllegalArgumentException("租户范围与最小租户角色定义不一致: " + name());
        }
        if (exposure == Exposure.PLATFORM_ADMIN_HTTP
                && actorRule != ActorRule.PLATFORM_ADMINISTRATOR) {
            throw new IllegalArgumentException("平台管理员入口缺少平台主体规则: " + name());
        }
        if (exposure == Exposure.INTERNAL_ONLY && actorRule != ActorRule.INTERNAL_SYSTEM) {
            throw new IllegalArgumentException("内部入口缺少系统主体规则: " + name());
        }
    }

    public enum ResourceScope {
        APP,
        TASK,
        PLATFORM,
        SYSTEM
    }

    public enum ActorRule {
        TENANT_ROLE,
        TASK_SUBMITTER_WITH_TENANT_ROLE,
        TENANT_ROLE_OR_PLATFORM_ADMINISTRATOR,
        PLATFORM_ADMINISTRATOR,
        INTERNAL_SYSTEM
    }

    public enum Exposure {
        TENANT_HTTP,
        PLATFORM_ADMIN_HTTP,
        INTERNAL_ONLY
    }
}
