package com.rush.rushaicodemother.service.provisioning;

import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingService;
import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingServiceFactory;
import com.rush.rushaicodemother.ai.intent.BackendIntentDetector;
import com.rush.rushaicodemother.ai.intent.DeterministicCodeGenTypeRouter;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.model.dto.app.AppAddRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeService;
import com.rush.rushaicodemother.service.artifact.AppArtifactLifecycleService;
import com.rush.rushaicodemother.service.lifecycle.AppOperationLockManager;
import com.rush.rushaicodemother.service.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultAppProvisioningServiceTest {

    private AppMapper appMapper;
    private AiModelRuntimeService aiModelService;
    private BackendIntentDetector backendIntentDetector;
    private DeterministicCodeGenTypeRouter deterministicCodeGenTypeRouter;
    private AiCodeGenTypeRoutingServiceFactory routingServiceFactory;
    private AppNameEnrichmentService appNameEnrichmentService;
    private ChatHistoryService chatHistoryService;
    private AppArtifactLifecycleService artifactLifecycleService;
    private AiCodeGenTypeRoutingService routingService;
    private TransactionOperations transactionOperations;
    private TenantProvisioningService tenantProvisioningService;
    private DefaultAppProvisioningService provisioningService;

    @BeforeEach
    void setUp() {
        appMapper = mock(AppMapper.class);
        aiModelService = mock(AiModelRuntimeService.class);
        backendIntentDetector = mock(BackendIntentDetector.class);
        deterministicCodeGenTypeRouter = new DeterministicCodeGenTypeRouter();
        routingServiceFactory = mock(AiCodeGenTypeRoutingServiceFactory.class);
        appNameEnrichmentService = mock(AppNameEnrichmentService.class);
        chatHistoryService = mock(ChatHistoryService.class);
        artifactLifecycleService = mock(AppArtifactLifecycleService.class);
        routingService = mock(AiCodeGenTypeRoutingService.class);
        transactionOperations = immediateTransactions();
        tenantProvisioningService = mock(TenantProvisioningService.class);

        when(routingServiceFactory.createAiCodeGenTypeRoutingService(any(Duration.class)))
                .thenReturn(routingService);
        when(backendIntentDetector.detectIntent(any(String.class)))
                .thenReturn(BackendIntentDetector.BackendIntentResult.none());
        when(routingService.routeCodeGenType(any(String.class))).thenReturn(CodeGenTypeEnum.VUE_PROJECT);
        when(appMapper.selectCopySourceState(11L)).thenReturn(sourceApp());
        when(tenantProvisioningService.requirePersonalTenantId(any(User.class))).thenReturn(700L);

        rebuildService();
    }

    @Test
    void shouldCreateApplicationImmediatelyAndScheduleNameEnrichment() {
        assignInsertedId(101L);
        AppAddRequest request = new AppAddRequest();
        request.setInitPrompt("  创建一个任务管理看板  ");

        Long appId = provisioningService.create(request, user(9L));

        assertEquals(101L, appId);
        ArgumentCaptor<App> appCaptor = ArgumentCaptor.forClass(App.class);
        verify(appMapper).insert(appCaptor.capture());
        App insertedApp = appCaptor.getValue();
        assertEquals("创建一个任务管理看板", insertedApp.getAppName());
        assertEquals("创建一个任务管理看板", insertedApp.getInitPrompt());
        assertEquals(CodeGenTypeEnum.VUE_PROJECT.getValue(), insertedApp.getCodeGenType());
        assertEquals(AppConstant.DEFAULT_APP_PRIORITY, insertedApp.getPriority());
        assertEquals(9L, insertedApp.getUserId());
        assertEquals(700L, insertedApp.getTenantId());
        assertNull(insertedApp.getDevServerPort());
        verify(aiModelService).ensureGenerationModelsConfigured();
        verifyNoInteractions(routingServiceFactory, routingService);
        verify(appNameEnrichmentService).schedule(
                101L, 9L, "创建一个任务管理看板", "创建一个任务管理看板");
    }

    @Test
    void shouldTruncateInitialNameWithoutWaitingForAi() {
        assignInsertedId(102L);
        AppAddRequest request = new AppAddRequest();
        request.setInitPrompt("  一个用于团队排期和任务跟踪的管理工具  ");

        provisioningService.create(request, user(9L));

        ArgumentCaptor<App> appCaptor = ArgumentCaptor.forClass(App.class);
        verify(appMapper).insert(appCaptor.capture());
        String fallbackName = appCaptor.getValue().getAppName();
        assertTrue(fallbackName.startsWith("一个用于团队排期"));
        assertTrue(fallbackName.length() <= 12);
        verify(appNameEnrichmentService).schedule(
                102L, 9L, "一个用于团队排期和任务跟踪的管理工具", fallbackName);
    }

    @Test
    void shouldPersistConstrainedBackendCodeType() {
        assignInsertedId(103L);
        when(backendIntentDetector.detectIntent(any(String.class)))
                .thenReturn(BackendIntentDetector.BackendIntentResult.explicitBackend());
        AppAddRequest request = new AppAddRequest();
        request.setInitPrompt("创建 Go 后端接口");

        provisioningService.create(request, user(9L));

        verify(appMapper).insert(argThat(app ->
                CodeGenTypeEnum.BACKEND_PROJECT.getValue().equals(app.getCodeGenType())));
        verifyNoInteractions(routingServiceFactory, routingService);
    }

    @Test
    void shouldUseBoundedAiRoutingOnlyForAmbiguousIntent() {
        assignInsertedId(105L);
        when(backendIntentDetector.detectIntent(any(String.class)))
                .thenReturn(BackendIntentDetector.BackendIntentResult.ambiguous());
        when(routingService.routeCodeGenType(any(String.class)))
                .thenReturn(CodeGenTypeEnum.FULL_STACK_PROJECT);
        when(backendIntentDetector.constrainCodeGenType(any(), any()))
                .thenReturn(CodeGenTypeEnum.FULL_STACK_PROJECT);
        AppAddRequest request = new AppAddRequest();
        request.setInitPrompt("创建 Vue 页面并连接 API");

        provisioningService.create(request, user(9L));

        verify(routingServiceFactory)
                .createAiCodeGenTypeRoutingService(Duration.ofSeconds(5));
        verify(appMapper).insert(argThat(app ->
                CodeGenTypeEnum.FULL_STACK_PROJECT.getValue().equals(app.getCodeGenType())));
    }

    @Test
    void shouldFallbackToFrontendWhenAmbiguousAiRoutingFails() {
        assignInsertedId(106L);
        when(backendIntentDetector.detectIntent(any(String.class)))
                .thenReturn(BackendIntentDetector.BackendIntentResult.ambiguous());
        when(routingService.routeCodeGenType(any(String.class)))
                .thenThrow(new IllegalStateException("routing unavailable"));
        AppAddRequest request = new AppAddRequest();
        request.setInitPrompt("创建 Vue 页面并连接 API");

        Long appId = provisioningService.create(request, user(9L));

        assertEquals(106L, appId);
        verify(appMapper).insert(argThat(app ->
                CodeGenTypeEnum.VUE_PROJECT.getValue().equals(app.getCodeGenType())));
    }

    @Test
    void shouldPropagateCreateCommitFailure() {
        BusinessException commitFailure = new BusinessException(ErrorCode.SYSTEM_ERROR, "commit failed");
        transactionOperations = transactionsFailingAfterCallback(commitFailure);
        rebuildService();
        assignInsertedId(104L);
        AppAddRequest request = new AppAddRequest();
        request.setInitPrompt("创建任务看板");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> provisioningService.create(request, user(9L))
        );

        assertSame(commitFailure, exception);
        verify(appMapper).insert(any(App.class));
        verifyNoInteractions(appNameEnrichmentService);
    }

    @Test
    void shouldRejectCopyWhenSourceDoesNotExistInsideOperationLock() {
        when(appMapper.selectCopySourceState(11L)).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> provisioningService.copy(11L, user(21L))
        );

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), exception.getCode());
        verify(appMapper).selectCopySourceState(11L);
        verify(appMapper, never()).insert(any(App.class));
        verifyNoInteractions(artifactLifecycleService, chatHistoryService);
    }

    @Test
    void shouldCopyApplicationWithoutRuntimeOrDeploymentState() {
        App sourceApp = sourceApp();
        assignInsertedId(201L);

        Long targetAppId = provisioningService.copy(11L, user(21L));

        assertEquals(201L, targetAppId);
        ArgumentCaptor<App> targetCaptor = ArgumentCaptor.forClass(App.class);
        verify(appMapper).insert(targetCaptor.capture());
        App targetApp = targetCaptor.getValue();
        assertEquals("source", targetApp.getAppName());
        assertEquals("cover.png", targetApp.getCover());
        assertEquals("source prompt", targetApp.getInitPrompt());
        assertEquals(CodeGenTypeEnum.HTML.getValue(), targetApp.getCodeGenType());
        assertEquals(21L, targetApp.getUserId());
        assertEquals(700L, targetApp.getTenantId());
        assertNull(targetApp.getDeployKey());
        assertNull(targetApp.getDeployedTime());
        assertNull(targetApp.getDevServerPort());
        assertNull(targetApp.getGeneratingMessage());
        verify(appMapper).selectCopySourceState(11L);
        verify(artifactLifecycleService).copyGeneratedArtifact(sourceApp, targetApp);
        verify(chatHistoryService).copyByAppId(11L, 201L, 21L);
    }

    @Test
    void shouldDeleteCopiedArtifactWhenHistoryCopyFails() {
        App sourceApp = sourceApp();
        assignInsertedId(202L);
        doThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "复制应用对话失败"))
                .when(chatHistoryService).copyByAppId(11L, 202L, 21L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> provisioningService.copy(11L, user(21L))
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        verify(artifactLifecycleService).deleteGeneratedArtifact(
                argThat(target -> target != null && Long.valueOf(202L).equals(target.getId())));
    }

    @Test
    void shouldCompensateArtifactWhenTransactionCommitFails() {
        BusinessException commitFailure = new BusinessException(ErrorCode.SYSTEM_ERROR, "commit failed");
        transactionOperations = transactionsFailingAfterCallback(commitFailure);
        rebuildService();
        App sourceApp = sourceApp();
        assignInsertedId(203L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> provisioningService.copy(11L, user(21L))
        );

        assertSame(commitFailure, exception);
        verify(artifactLifecycleService).deleteGeneratedArtifact(
                argThat(target -> target != null && Long.valueOf(203L).equals(target.getId())));
    }

    @Test
    void shouldNotDeleteTargetWhenArtifactCopyItselfFails() {
        App sourceApp = sourceApp();
        assignInsertedId(204L);
        BusinessException copyFailure = new BusinessException(ErrorCode.SYSTEM_ERROR, "copy failed");
        doThrow(copyFailure).when(artifactLifecycleService)
                .copyGeneratedArtifact(any(App.class), any(App.class));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> provisioningService.copy(11L, user(21L))
        );

        assertSame(copyFailure, exception);
        verify(artifactLifecycleService, never()).deleteGeneratedArtifact(any(App.class));
    }

    @Test
    void shouldPreserveCopyAndCleanupFailuresWhenCompensationFails() {
        App sourceApp = sourceApp();
        assignInsertedId(205L);
        doThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "复制应用对话失败"))
                .when(chatHistoryService).copyByAppId(11L, 205L, 21L);
        BusinessException cleanupFailure = new BusinessException(ErrorCode.SYSTEM_ERROR, "cleanup failed");
        doThrow(cleanupFailure).when(artifactLifecycleService).deleteGeneratedArtifact(any(App.class));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> provisioningService.copy(11L, user(21L))
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), ((BusinessException) exception.getCause()).getCode());
        assertEquals(1, exception.getSuppressed().length);
        assertSame(cleanupFailure, exception.getSuppressed()[0]);
    }

    private void rebuildService() {
        provisioningService = new DefaultAppProvisioningService(
                appMapper,
                aiModelService,
                backendIntentDetector,
                deterministicCodeGenTypeRouter,
                routingServiceFactory,
                appNameEnrichmentService,
                chatHistoryService,
                artifactLifecycleService,
                new AppOperationLockManager(),
                tenantProvisioningService,
                transactionOperations
        );
    }

    private void assignInsertedId(Long appId) {
        doAnswer(invocation -> {
            App app = invocation.getArgument(0);
            app.setId(appId);
            return 1;
        }).when(appMapper).insert(any(App.class));
    }

    private TransactionOperations immediateTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(mock(TransactionStatus.class));
            }
        };
    }

    private TransactionOperations transactionsFailingAfterCallback(RuntimeException failure) {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                action.doInTransaction(mock(TransactionStatus.class));
                throw failure;
            }
        };
    }

    private App sourceApp() {
        return App.builder()
                .id(11L)
                .userId(20L)
                .appName("source")
                .cover("cover.png")
                .initPrompt("source prompt")
                .codeGenType(CodeGenTypeEnum.HTML.getValue())
                .deployKey("Existing11")
                .devServerPort(12000)
                .generatingMessage("running")
                .build();
    }

    private User user(Long userId) {
        return User.builder().id(userId).build();
    }
}
