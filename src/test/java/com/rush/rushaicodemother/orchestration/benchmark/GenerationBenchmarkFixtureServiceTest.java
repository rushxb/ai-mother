package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.security.password.PasswordHashService;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.lifecycle.AppDeletionService;
import com.rush.rushaicodemother.service.tenant.TenantProvisioningService;
import com.rush.rushaicodemother.service.user.UserPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationBenchmarkFixtureServiceTest {

    private UserPersistenceService users;
    private AppPersistenceService apps;
    private VueProjectTemplateBootstrapService vueBootstrap;
    private BackendProjectTemplateBootstrapService backendBootstrap;
    private GenerationWorkspaceService workspaceService;
    private GenerationBenchmarkValidationEngine validationEngine;
    private AppDeletionService deletionService;
    private GenerationBenchmarkFixtureService service;

    @BeforeEach
    void setUp() {
        users = mock(UserPersistenceService.class);
        apps = mock(AppPersistenceService.class);
        PasswordHashService passwordHashService = mock(PasswordHashService.class);
        vueBootstrap = mock(VueProjectTemplateBootstrapService.class);
        backendBootstrap = mock(BackendProjectTemplateBootstrapService.class);
        workspaceService = mock(GenerationWorkspaceService.class);
        validationEngine = mock(GenerationBenchmarkValidationEngine.class);
        deletionService = mock(AppDeletionService.class);
        TenantProvisioningService tenantProvisioningService = mock(TenantProvisioningService.class);
        when(passwordHashService.hash(any())).thenReturn("$2a$12$benchmark-hash");
        when(workspaceService.resolve(anyLong(), any())).thenReturn(workspace());
        when(validationEngine.prepare(any(), any(), anyLong()))
                .thenReturn(GenerationBenchmarkValidationPlan.empty());
        when(tenantProvisioningService.requirePersonalTenantId(any(User.class))).thenReturn(700L);
        service = new GenerationBenchmarkFixtureService(
                users,
                apps,
                passwordHashService,
                new GenerationBenchmarkRequestFactory(),
                vueBootstrap,
                backendBootstrap,
                workspaceService,
                validationEngine,
                deletionService,
                tenantProvisioningService
        );
    }

    @Test
    void createTaskMustUsePersistedPositiveIdentitiesWithoutEditBootstrap() {
        User user = user(9L);
        when(users.findActiveByAccount(GenerationBenchmarkFixtureService.BENCHMARK_ACCOUNT)).thenReturn(user);
        when(apps.createPrepared(any())).thenReturn(101L);
        when(apps.findActiveById(101L)).thenReturn(app(101L, user.getId(), "vue_project"));

        try (GenerationBenchmarkFixture fixture = service.create(new GenerationBenchmarkTask(
                "create_vue", "CREATE", "vue_project", "build dashboard", "build"))) {
            assertTrue(fixture.request().app().getId() > 0);
            assertTrue(fixture.request().loginUser().getId() > 0);
        }

        verify(vueBootstrap, never()).bootstrapIfNecessary(any(), any(), any());
        verify(backendBootstrap, never()).bootstrapIfNecessary(any(), any());
        verify(validationEngine).prepare(any(), any(), eq(user.getId()));
        verify(deletionService).delete(101L);
    }

    @Test
    void fullStackEditMustBootstrapBothSidesBeforeExecution() {
        User user = user(9L);
        when(users.findActiveByAccount(GenerationBenchmarkFixtureService.BENCHMARK_ACCOUNT)).thenReturn(user);
        when(apps.createPrepared(any())).thenReturn(102L);
        when(apps.findActiveById(102L)).thenReturn(app(102L, user.getId(), "full_stack_project"));

        try (GenerationBenchmarkFixture ignored = service.create(new GenerationBenchmarkTask(
                "edit_fullstack", "AGENT_EDIT", "full_stack_project", "add category", "build"))) {
            verify(vueBootstrap).bootstrapIfNecessary(eq(102L), any(), eq("add category"));
            verify(backendBootstrap).bootstrapIfNecessary(eq(102L), any());
        }

        verify(deletionService).delete(102L);
    }

    @Test
    void missingBenchmarkUserMustBeCreatedAndReloaded() {
        User persisted = user(77L);
        when(users.findActiveByAccount(GenerationBenchmarkFixtureService.BENCHMARK_ACCOUNT)).thenReturn(null);
        when(users.createUser(any())).thenReturn(77L);
        when(users.findActiveById(77L)).thenReturn(persisted);
        when(apps.createPrepared(any())).thenReturn(103L);
        when(apps.findActiveById(103L)).thenReturn(app(103L, 77L, "backend_project"));

        try (GenerationBenchmarkFixture fixture = service.create(new GenerationBenchmarkTask(
                "create_backend", "CREATE", "backend_project", "build API", "build"))) {
            assertEquals(77L, fixture.request().loginUser().getId());
        }

        verify(users).createUser(any());
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUserAccount(GenerationBenchmarkFixtureService.BENCHMARK_ACCOUNT);
        return user;
    }

    private App app(Long id, Long userId, String type) {
        return App.builder()
                .id(id)
                .userId(userId)
                .appName("benchmark")
                .initPrompt("benchmark prompt")
                .codeGenType(type)
                .build();
    }

    private GenerationWorkspace workspace() {
        Path root = Path.of("target", "benchmark-fixture-test").toAbsolutePath().normalize();
        return new GenerationWorkspace(
                101L, null, root, root, true, root, null, Set.of(), Set.of());
    }
}
