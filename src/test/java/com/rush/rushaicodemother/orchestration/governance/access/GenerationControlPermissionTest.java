package com.rush.rushaicodemother.orchestration.governance.access;

import com.rush.rushaicodemother.model.enums.TenantRole;
import org.junit.jupiter.api.Test;

import static com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission.ActorRule.INTERNAL_SYSTEM;
import static com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission.ActorRule.PLATFORM_ADMINISTRATOR;
import static com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission.ActorRule.TASK_SUBMITTER_WITH_TENANT_ROLE;
import static com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission.ActorRule.TENANT_ROLE;
import static com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission.ActorRule.TENANT_ROLE_OR_PLATFORM_ADMINISTRATOR;
import static com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission.Exposure.INTERNAL_ONLY;
import static com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission.Exposure.PLATFORM_ADMIN_HTTP;
import static com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission.Exposure.TENANT_HTTP;
import static com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission.ResourceScope.APP;
import static com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission.ResourceScope.PLATFORM;
import static com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission.ResourceScope.SYSTEM;
import static com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission.ResourceScope.TASK;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationControlPermissionTest {

    @Test
    void matrixMustFreezeEveryRequiredControlPlaneOperation() {
        assertAll(
                () -> assertPermission(GenerationControlPermission.TASK_SUBMIT,
                        APP, TenantRole.DEVELOPER, TENANT_ROLE, TENANT_HTTP),
                () -> assertPermission(GenerationControlPermission.TASK_QUERY,
                        TASK, TenantRole.VIEWER, TENANT_ROLE, TENANT_HTTP),
                () -> assertPermission(GenerationControlPermission.TASK_CANCEL,
                        TASK, TenantRole.DEVELOPER, TENANT_ROLE, TENANT_HTTP),
                () -> assertPermission(GenerationControlPermission.TOOL_APPROVAL,
                        TASK, TenantRole.DEVELOPER,
                        TASK_SUBMITTER_WITH_TENANT_ROLE, TENANT_HTTP),
                () -> assertPermission(GenerationControlPermission.TASK_RECOVERY,
                        SYSTEM, null, INTERNAL_SYSTEM, INTERNAL_ONLY),
                () -> assertPermission(GenerationControlPermission.TERMINAL_EFFECT_REPLAY,
                        PLATFORM, null, PLATFORM_ADMINISTRATOR, PLATFORM_ADMIN_HTTP),
                () -> assertPermission(GenerationControlPermission.BENCHMARK_MANAGE,
                        PLATFORM, null, PLATFORM_ADMINISTRATOR, PLATFORM_ADMIN_HTTP),
                () -> assertPermission(GenerationControlPermission.MODEL_CONFIGURE,
                        PLATFORM, null, PLATFORM_ADMINISTRATOR, PLATFORM_ADMIN_HTTP),
                () -> assertPermission(GenerationControlPermission.APP_DELETE,
                        APP, TenantRole.ADMIN,
                        TENANT_ROLE_OR_PLATFORM_ADMINISTRATOR, TENANT_HTTP)
        );
    }

    private void assertPermission(GenerationControlPermission permission,
                                  GenerationControlPermission.ResourceScope scope,
                                  TenantRole role,
                                  GenerationControlPermission.ActorRule actorRule,
                                  GenerationControlPermission.Exposure exposure) {
        assertEquals(scope, permission.resourceScope());
        assertEquals(role, permission.minimumTenantRole());
        assertEquals(actorRule, permission.actorRule());
        assertEquals(exposure, permission.exposure());
    }
}
