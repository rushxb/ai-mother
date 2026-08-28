package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapRegistry;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapResult;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.security.password.PasswordHashService;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.lifecycle.AppDeletionService;
import com.rush.rushaicodemother.service.tenant.TenantProvisioningService;
import com.rush.rushaicodemother.service.user.UserPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    private GenerationTemplateBootstrapRegistry templateBootstrapRegistry;
    private GenerationWorkspaceService workspaceService;
    private GenerationBenchmarkValidationEngine validationEngine;
    private AppDeletionService deletionService;
    private GenerationBenchmarkFixtureService service;

    @BeforeEach
    void setUp() {
        users = mock(UserPersistenceService.class);
        apps = mock(AppPersistenceService.class);
        PasswordHashService passwordHashService = mock(PasswordHashService.class);
        templateBootstrapRegistry = mock(GenerationTemplateBootstrapRegistry.class);
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
                templateBootstrapRegistry,
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

        verify(templateBootstrapRegistry, never()).bootstrap(any(), any(), any());
        verify(validationEngine).prepare(any(), any(), eq(user.getId()));
        verify(deletionService).delete(101L);
    }

    @Test
    void fullStackEditMustBootstrapBothSidesBeforeExecution() {
        User user = user(9L);
        when(users.findActiveByAccount(GenerationBenchmarkFixtureService.BENCHMARK_ACCOUNT)).thenReturn(user);
        when(apps.createPrepared(any())).thenReturn(102L);
        when(apps.findActiveById(102L)).thenReturn(app(102L, user.getId(), "full_stack_project"));
        GenerationWorkspace workspace = workspace(102L, CodeGenTypeEnum.FULL_STACK_PROJECT);
        when(templateBootstrapRegistry.bootstrap(
                102L, CodeGenTypeEnum.FULL_STACK_PROJECT, "add category"))
                .thenReturn(bootstrapResult(workspace));

        try (GenerationBenchmarkFixture ignored = service.create(new GenerationBenchmarkTask(
                "edit_fullstack", "AGENT_EDIT", "full_stack_project", "add category", "build"))) {
            verify(templateBootstrapRegistry).bootstrap(
                    102L, CodeGenTypeEnum.FULL_STACK_PROJECT, "add category");
        }

        verify(workspaceService, never()).resolve(anyLong(), any());
        verify(validationEngine).prepare(any(), eq(workspace), eq(user.getId()));
        verify(deletionService).delete(102L);
    }

    @Test
    void crossTypeUpgradeMustCreateAndBootstrapTheDeclaredSourceProject() {
        User user = user(9L);
        when(users.findActiveByAccount(GenerationBenchmarkFixtureService.BENCHMARK_ACCOUNT))
                .thenReturn(user);
        when(apps.createPrepared(any())).thenReturn(104L);
        when(apps.findActiveById(104L)).thenReturn(app(104L, user.getId(), "vue_project"));
        GenerationWorkspace sourceWorkspace = workspace(104L, CodeGenTypeEnum.VUE_PROJECT);
        when(templateBootstrapRegistry.bootstrap(
                104L, CodeGenTypeEnum.VUE_PROJECT, "升级为全栈项目"))
                .thenReturn(bootstrapResult(sourceWorkspace));

        GenerationBenchmarkTask task = crossTypeTask();
        try (GenerationBenchmarkFixture fixture = service.create(task)) {
            assertEquals("vue_project", fixture.request().app().getCodeGenType());
            verify(templateBootstrapRegistry).bootstrap(
                    104L, CodeGenTypeEnum.VUE_PROJECT, "升级为全栈项目");
            verify(validationEngine).prepare(task, sourceWorkspace, user.getId());
        }

        ArgumentCaptor<AppPersistenceService.NewApp> appCaptor =
                ArgumentCaptor.forClass(AppPersistenceService.NewApp.class);
        verify(apps).createPrepared(appCaptor.capture());
        assertEquals("vue_project", appCaptor.getValue().codeGenType());
        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT, task.targetProjectType());
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

    private GenerationBenchmarkTask crossTypeTask() {
        return new GenerationBenchmarkTask(
                "upgrade_vue_fullstack",
                "HEAVY_EXPERT",
                "full_stack_project",
                "升级为全栈项目",
                "build",
                "cross_type_upgrade",
                GenerationBenchmarkDifficulty.HARD,
                List.of("project_migration"),
                List.of(
                        GenerationBenchmarkQualityDimension.STRUCTURAL,
                        GenerationBenchmarkQualityDimension.FUNCTIONAL,
                        GenerationBenchmarkQualityDimension.DIFF_SCOPE,
                        GenerationBenchmarkQualityDimension.SECURITY,
                        GenerationBenchmarkQualityDimension.RUNTIME,
                        GenerationBenchmarkQualityDimension.VISUAL),
                List.of(),
                List.of(),
                "HEAVY_EXPERT",
                List.of("CREATE", "LIGHT_EDIT", "AGENT_EDIT"),
                com.rush.rushaicodemother.orchestration.intent.IntentOperationType.EDIT,
                GenerationBenchmarkFixtureKind.TEMPLATE_PROJECT,
                List.of(),
                "vue_project"
        );
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
        return workspace(101L, CodeGenTypeEnum.VUE_PROJECT);
    }

    private GenerationWorkspace workspace(Long appId, CodeGenTypeEnum codeGenType) {
        Path root = Path.of("target", "benchmark-fixture-test").toAbsolutePath().normalize();
        return new GenerationWorkspace(
                appId, codeGenType, root, root, true, root, null, Set.of(), Set.of());
    }

    private GenerationTemplateBootstrapResult bootstrapResult(GenerationWorkspace workspace) {
        return new GenerationTemplateBootstrapResult(
                workspace.codeGenType(),
                true,
                true,
                workspace,
                "项目模板",
                "项目模板已就绪",
                Map.of("bootstrapped", true),
                Map.of(),
                Map.of()
        );
    }
}
