package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.testing.GenerationFailureMatrix;
import com.rush.rushaicodemother.config.ArtifactLifecycleProperties;
import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.service.artifact.ArtifactPathMover;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag(GenerationFailureMatrix.TAG)
class GenerationWorkspacePublicationServiceTest {

    private static final Long APP_ID = 11L;
    private static final CodeGenTypeEnum CODE_GEN_TYPE = CodeGenTypeEnum.VUE_PROJECT;
    private static final Instant NOW = Instant.parse("2026-07-20T08:00:00Z");

    @TempDir
    Path tempDirectory;

    @Test
    void successfulPublicationMustActivateFilesystemBeforeTransactionalMetadataCommit() throws Exception {
        Fixture fixture = fixture("task-success", 5L);
        stubJournal(fixture, GenerationWorkspacePublicationJournalStatus.PREPARED);
        GenerationWorkspacePublicationCommitter committer =
                mock(GenerationWorkspacePublicationCommitter.class);

        GenerationWorkspacePublicationResult result = fixture.service().publishWithMetadata(
                fixture.fence(), fixture.executionWorkspace(), committer);

        assertEquals(GenerationWorkspacePublicationResult.Status.PUBLISHED, result.status());
        assertFalse(Files.exists(fixture.source()));
        assertEquals(result.pointer(), fixture.catalog().findCurrent(APP_ID, CODE_GEN_TYPE).orElseThrow());
        assertEquals(result.publishedWorkspace(), fixture.catalog().resolveWorkspace(result.pointer()));
        var ordered = inOrder(fixture.journal(), committer);
        ordered.verify(fixture.journal()).prepare(any(), eq(NOW));
        ordered.verify(fixture.journal()).markFilesystemActivated(result.pointer(), NOW);
        ordered.verify(committer).commit(result.pointer());
        verify(fixture.runtimeLifecycleService(), times(3))
                .renewForCriticalSection(fixture.fence());
        verify(fixture.fenceGuard(), times(3)).assertCurrent(fixture.fence());
    }

    @Test
    void metadataFailureMustRestorePreviousPointerAndExecutionWorkspace() throws Exception {
        Fixture fixture = fixture("task-metadata-failure", 5L);
        GenerationWorkspacePublicationPointer previous = pointer(
                "task-previous", 4L, NOW.minusSeconds(60));
        prepareActivePublication(fixture.catalog(), previous);
        stubJournal(fixture, GenerationWorkspacePublicationJournalStatus.PREPARED);
        GenerationWorkspacePublicationCommitter committer =
                mock(GenerationWorkspacePublicationCommitter.class);
        IllegalStateException failure = new IllegalStateException("metadata commit failed");
        doThrow(failure).when(committer).commit(any());
        GenerationWorkspacePublicationPointer candidate = pointer(
                fixture.fence().taskId(), fixture.fence().executionEpoch(), NOW);

        GenerationWorkspacePublicationException thrown = assertThrows(
                GenerationWorkspacePublicationException.class,
                () -> fixture.service().publishWithMetadata(
                        fixture.fence(), fixture.executionWorkspace(), committer));

        assertSame(failure, thrown.getCause());
        assertTrue(thrown.safelyRolledBack());
        assertEquals(previous, fixture.catalog().findCurrent(APP_ID, CODE_GEN_TYPE).orElseThrow());
        assertTrue(Files.isDirectory(fixture.source()));
        assertTrue(Files.exists(fixture.source().resolve("package.json")));
        assertFalse(Files.exists(fixture.catalog().versionWorkspacePath(candidate)));
        verify(fixture.journal()).markFilesystemActivated(candidate, NOW);
        verify(fixture.journal()).markRolledBack(eq(candidate), anyString(), eq(NOW));
        verify(fixture.journal(), never()).markRollbackRequired(any(), anyString(), any());
    }

    @Test
    void failedPointerRollbackMustKeepActivePublishedDirectoryForRollForward() throws Exception {
        Fixture fixture = fixture("task-rollback-failure", 5L);
        GenerationWorkspacePublicationPointer previous = pointer(
                "task-previous", 4L, NOW.minusSeconds(60));
        prepareActivePublication(fixture.catalog(), previous);
        stubJournal(fixture, GenerationWorkspacePublicationJournalStatus.PREPARED);
        GenerationWorkspacePublicationCommitter committer =
                mock(GenerationWorkspacePublicationCommitter.class);
        IllegalStateException failure = new IllegalStateException("metadata commit failed");
        doThrow(failure).when(committer).commit(any());
        doThrow(new IllegalStateException("pointer restore failed"))
                .when(fixture.catalog())
                .restore(eq(APP_ID), eq(CODE_GEN_TYPE),
                        any(GenerationWorkspacePublicationCatalog.PointerSnapshot.class));
        GenerationWorkspacePublicationPointer candidate = pointer(
                fixture.fence().taskId(), fixture.fence().executionEpoch(), NOW);

        GenerationWorkspacePublicationException thrown = assertThrows(
                GenerationWorkspacePublicationException.class,
                () -> fixture.service().publishWithMetadata(
                        fixture.fence(), fixture.executionWorkspace(), committer));

        assertSame(failure, thrown.getCause());
        assertFalse(thrown.safelyRolledBack());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("pointer restore failed", failure.getSuppressed()[0].getMessage());
        assertEquals(candidate, fixture.catalog().findCurrent(APP_ID, CODE_GEN_TYPE).orElseThrow());
        assertFalse(Files.exists(fixture.source()));
        assertEquals(fixture.catalog().versionWorkspacePath(candidate).toRealPath(),
                fixture.catalog().resolveWorkspace(candidate));
        verify(fixture.journal()).markRollbackRequired(eq(candidate), anyString(), eq(NOW));
        verify(fixture.journal(), never()).markRolledBack(any(), anyString(), any());
    }

    @Test
    void activePointerWithUncommittedJournalMustRollForwardWithoutMovingAgain() throws Exception {
        Fixture fixture = fixture("task-crash-window", 5L);
        deleteExecutionWorkspace(fixture.source());
        GenerationWorkspacePublicationPointer active = pointer(
                fixture.fence().taskId(), fixture.fence().executionEpoch(), NOW.minusSeconds(10));
        prepareActivePublication(fixture.catalog(), active);
        when(fixture.journal().prepare(active, NOW)).thenReturn(entry(
                active, GenerationWorkspacePublicationJournalStatus.FILESYSTEM_ACTIVATED));
        GenerationWorkspacePublicationCommitter committer =
                mock(GenerationWorkspacePublicationCommitter.class);

        GenerationWorkspacePublicationResult result = fixture.service().publishWithMetadata(
                fixture.fence(), fixture.executionWorkspace(), committer);

        assertEquals(GenerationWorkspacePublicationResult.Status.ALREADY_PUBLISHED, result.status());
        assertEquals(active, result.pointer());
        verify(committer).commit(active);
        verify(fixture.pathMover(), never()).move(any(), any());
        verify(fixture.journal(), never()).markFilesystemActivated(any(), any());
        verify(fixture.runtimeLifecycleService()).renewForCriticalSection(fixture.fence());
        verify(fixture.fenceGuard()).assertCurrent(fixture.fence());
    }

    @Test
    void committedActivePublicationMustNotRepeatMetadataCommit() throws Exception {
        Fixture fixture = fixture("task-committed", 5L);
        deleteExecutionWorkspace(fixture.source());
        GenerationWorkspacePublicationPointer active = pointer(
                fixture.fence().taskId(), fixture.fence().executionEpoch(), NOW.minusSeconds(10));
        prepareActivePublication(fixture.catalog(), active);
        when(fixture.journal().prepare(active, NOW)).thenReturn(entry(
                active, GenerationWorkspacePublicationJournalStatus.COMMITTED));
        GenerationWorkspacePublicationCommitter committer =
                mock(GenerationWorkspacePublicationCommitter.class);

        GenerationWorkspacePublicationResult result = fixture.service().publishWithMetadata(
                fixture.fence(), fixture.executionWorkspace(), committer);

        assertEquals(GenerationWorkspacePublicationResult.Status.ALREADY_PUBLISHED, result.status());
        verify(committer, never()).commit(any());
        verify(fixture.pathMover(), never()).move(any(), any());
        verify(fixture.runtimeLifecycleService(), never()).renewForCriticalSection(any());
        verify(fixture.fenceGuard(), never()).assertCurrent(any(GenerationExecutionFence.class));
    }

    @Test
    void preparedJournalWhoseOwningLeaseExpiredMustBeRolledBackForTaskRetry() throws Exception {
        Fixture fixture = fixture("task-expired-before-activation", 5L);
        GenerationWorkspacePublicationPointer prepared = pointer(
                fixture.fence().taskId(), fixture.fence().executionEpoch(), NOW.minusSeconds(10));
        when(fixture.journal().rollbackPreparedIfOwningExecutionExpired(
                eq(prepared), anyString(), eq(NOW))).thenReturn(true);

        GenerationWorkspacePublicationService.ReconciliationOutcome outcome =
                fixture.service().reconcile(
                        entry(prepared, GenerationWorkspacePublicationJournalStatus.PREPARED),
                        mock(GenerationWorkspacePublicationCommitter.class));

        assertEquals(
                GenerationWorkspacePublicationService.ReconciliationOutcome.ROLLED_BACK,
                outcome);
    }

    @Test
    void preparedJournalWhoseOwningLeaseIsStillLiveMustRemainPending() throws Exception {
        Fixture fixture = fixture("task-live-before-activation", 5L);
        GenerationWorkspacePublicationPointer prepared = pointer(
                fixture.fence().taskId(), fixture.fence().executionEpoch(), NOW.minusSeconds(10));
        when(fixture.journal().rollbackPreparedIfOwningExecutionExpired(
                eq(prepared), anyString(), eq(NOW))).thenReturn(false);

        GenerationWorkspacePublicationService.ReconciliationOutcome outcome =
                fixture.service().reconcile(
                        entry(prepared, GenerationWorkspacePublicationJournalStatus.PREPARED),
                        mock(GenerationWorkspacePublicationCommitter.class));

        assertEquals(
                GenerationWorkspacePublicationService.ReconciliationOutcome.PENDING_TASK_RETRY,
                outcome);
    }

    @Test
    void failedPreflightAfterPrepareMustCloseTheJournalIntent() throws Exception {
        Fixture fixture = fixture("task-preflight-failure", 5L);
        deleteExecutionWorkspace(fixture.source());
        stubJournal(fixture, GenerationWorkspacePublicationJournalStatus.PREPARED);
        GenerationWorkspacePublicationCommitter committer =
                mock(GenerationWorkspacePublicationCommitter.class);
        GenerationWorkspacePublicationPointer candidate = pointer(
                fixture.fence().taskId(), fixture.fence().executionEpoch(), NOW);

        assertThrows(BusinessException.class, () -> fixture.service().publishWithMetadata(
                fixture.fence(), fixture.executionWorkspace(), committer));

        verify(fixture.journal()).markRolledBack(eq(candidate), anyString(), eq(NOW));
        verify(fixture.journal(), never()).markFilesystemActivated(any(), any());
        verify(committer, never()).commit(any());
    }

    @Test
    void managedPublicationLockWaitMustRespectTaskDeadline() throws Exception {
        Fixture fixture = fixture("task-lock-deadline", 5L);
        fixture.lifecycleProperties().setPublishRetryDelayMillis(5_000L);
        GenerationSession session = managedSession(fixture, Duration.ofMillis(250));
        GenerationWorkspacePublicationCommitter committer =
                mock(GenerationWorkspacePublicationCommitter.class);
        Path lockPath = fixture.catalog().lockPath(APP_ID);
        CountDownLatch lockRequested = new CountDownLatch(1);
        doAnswer(invocation -> {
            lockRequested.countDown();
            return lockPath;
        }).when(fixture.catalog()).lockPath(APP_ID);

        try (FileChannel blockerChannel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = blockerChannel.lock();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Throwable> outcome = executor.submit(() -> publicationFailure(
                    fixture, session, committer));

            assertTrue(lockRequested.await(1, TimeUnit.SECONDS));
            Throwable failure = outcome.get(1, TimeUnit.SECONDS);

            assertInstanceOf(GenerationDeadlineExceededException.class, failure);
            verify(fixture.journal(), never()).prepare(any(), any());
            verify(committer, never()).commit(any());
        }
    }

    @Test
    void managedPublicationLockWaitMustObserveCancellationPromptly() throws Exception {
        Fixture fixture = fixture("task-lock-cancelled", 5L);
        fixture.lifecycleProperties().setPublishRetryDelayMillis(5_000L);
        GenerationSession session = managedSession(fixture, Duration.ofSeconds(10));
        GenerationWorkspacePublicationCommitter committer =
                mock(GenerationWorkspacePublicationCommitter.class);
        Path lockPath = fixture.catalog().lockPath(APP_ID);
        CountDownLatch lockRequested = new CountDownLatch(1);
        doAnswer(invocation -> {
            lockRequested.countDown();
            return lockPath;
        }).when(fixture.catalog()).lockPath(APP_ID);

        try (FileChannel blockerChannel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = blockerChannel.lock();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Throwable> outcome = executor.submit(() -> publicationFailure(
                    fixture, session, committer));

            assertTrue(lockRequested.await(1, TimeUnit.SECONDS));
            Thread.sleep(50L);
            session.cancel("测试取消发布锁等待");
            Throwable failure = outcome.get(1, TimeUnit.SECONDS);

            assertInstanceOf(GenerationExecutionCancelledException.class, failure);
            verify(fixture.journal(), never()).prepare(any(), any());
            verify(committer, never()).commit(any());
        }
    }

    private Throwable publicationFailure(Fixture fixture,
                                         GenerationSession session,
                                         GenerationWorkspacePublicationCommitter committer) {
        try {
            fixture.service().publishWithMetadata(session, committer);
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private GenerationSession managedSession(Fixture fixture, Duration taskTimeout) {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 2);
        }
        Clock contextClock = Clock.systemUTC();
        GenerationExecutionContext executionContext = new GenerationExecutionContext(
                fixture.fence().taskId(),
                APP_ID,
                22L,
                contextClock.instant(),
                new GenerationExecutionLimits(
                        taskTimeout,
                        taskTimeout.compareTo(Duration.ofSeconds(1)) < 0
                                ? taskTimeout
                                : Duration.ofSeconds(1),
                        Duration.ofMillis(10),
                        budgets),
                contextClock);
        executionContext.bindExecutionFence(fixture.fence());
        GenerationSession session = new GenerationSession(null, executionContext);
        session.bindExecutionWorkspace(fixture.executionWorkspace());
        return session;
    }

    private Fixture fixture(String taskId, long epoch) throws Exception {
        Path fixtureRoot = tempDirectory.resolve(taskId);
        CodeStorageProperties storageProperties = new CodeStorageProperties();
        storageProperties.setOutputRootDir(fixtureRoot.resolve("output"));
        storageProperties.setDeployRootDir(fixtureRoot.resolve("deploy"));
        storageProperties.setSnapshotRootDir(fixtureRoot.resolve("snapshot"));
        GenerationWorkspacePublicationCatalog catalog = spy(
                new GenerationWorkspacePublicationCatalog(storageProperties));
        ArtifactLifecycleProperties lifecycleProperties = new ArtifactLifecycleProperties();
        lifecycleProperties.setPublicationLockTimeout(Duration.ofSeconds(2));
        lifecycleProperties.setPublishRetryDelayMillis(1);
        ArtifactPathMover pathMover = spy(new ArtifactPathMover(lifecycleProperties));
        GenerationTaskRuntimeLifecycleService runtimeLifecycleService =
                mock(GenerationTaskRuntimeLifecycleService.class);
        GenerationTaskFenceGuard fenceGuard = mock(GenerationTaskFenceGuard.class);
        GenerationWorkspacePublicationJournalRepository journal =
                mock(GenerationWorkspacePublicationJournalRepository.class);
        GenerationExecutionFence fence = new GenerationExecutionFence(taskId, "worker-1", epoch);

        Path epochRoot = Files.createDirectories(
                fixtureRoot.resolve("executions").resolve("epoch-" + epoch)).toRealPath();
        Path typeRoot = Files.createDirectory(epochRoot.resolve(CODE_GEN_TYPE.getValue())).toRealPath();
        Path source = Files.createDirectory(typeRoot.resolve("workspace")).toRealPath();
        Files.writeString(source.resolve("package.json"), "{}");
        GenerationWorkspace workspace = new GenerationWorkspace(
                APP_ID,
                CODE_GEN_TYPE,
                source,
                source,
                true,
                source,
                null,
                Set.of(),
                Set.of("json", "vue", "ts")
        );
        GenerationExecutionWorkspace executionWorkspace = new GenerationExecutionWorkspace(
                APP_ID, fence, CODE_GEN_TYPE, epochRoot, typeRoot, workspace, null);
        GenerationWorkspacePublicationService service = new GenerationWorkspacePublicationService(
                catalog,
                runtimeLifecycleService,
                fenceGuard,
                pathMover,
                lifecycleProperties,
                journal,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        return new Fixture(
                fence, executionWorkspace, source, catalog, pathMover,
                lifecycleProperties, runtimeLifecycleService, fenceGuard, journal, service);
    }

    private void stubJournal(Fixture fixture,
                             GenerationWorkspacePublicationJournalStatus status) {
        when(fixture.journal().prepare(any(), eq(NOW))).thenAnswer(invocation ->
                entry(invocation.getArgument(0), status));
    }

    private GenerationWorkspacePublicationJournalEntry entry(
            GenerationWorkspacePublicationPointer pointer,
            GenerationWorkspacePublicationJournalStatus status) {
        return new GenerationWorkspacePublicationJournalEntry(
                pointer.taskId(), pointer.appId(), pointer.codeGenType(), pointer.executionEpoch(),
                pointer.publishedAt(), status, 0, 1L, "");
    }

    private GenerationWorkspacePublicationPointer pointer(String taskId,
                                                           long epoch,
                                                           Instant publishedAt) {
        return new GenerationWorkspacePublicationPointer(
                GenerationWorkspacePublicationPointer.CURRENT_SCHEMA_VERSION,
                APP_ID,
                CODE_GEN_TYPE,
                taskId,
                epoch,
                publishedAt
        );
    }

    private Path prepareActivePublication(GenerationWorkspacePublicationCatalog catalog,
                                          GenerationWorkspacePublicationPointer pointer)
            throws Exception {
        Path workspace = catalog.prepareVersionParent(pointer).resolve("workspace");
        Files.createDirectory(workspace);
        Files.writeString(workspace.resolve("package.json"), "{}");
        catalog.writeOwnerMarker(workspace, pointer);
        catalog.activate(pointer);
        return workspace.toRealPath();
    }

    private void deleteExecutionWorkspace(Path source) throws Exception {
        Files.deleteIfExists(source.resolve("package.json"));
        Files.delete(source);
    }

    private record Fixture(
            GenerationExecutionFence fence,
            GenerationExecutionWorkspace executionWorkspace,
            Path source,
            GenerationWorkspacePublicationCatalog catalog,
            ArtifactPathMover pathMover,
            ArtifactLifecycleProperties lifecycleProperties,
            GenerationTaskRuntimeLifecycleService runtimeLifecycleService,
            GenerationTaskFenceGuard fenceGuard,
            GenerationWorkspacePublicationJournalRepository journal,
            GenerationWorkspacePublicationService service
    ) {
    }
}
