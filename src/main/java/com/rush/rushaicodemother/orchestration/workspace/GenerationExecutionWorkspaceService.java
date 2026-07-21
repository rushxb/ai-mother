package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.config.ArtifactLifecycleProperties;
import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.service.artifact.ArtifactCopyException;
import com.rush.rushaicodemother.service.artifact.ArtifactDirectoryCopier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** Creates and restores task/epoch-owned workspaces below the generated-code filesystem. */
@Slf4j
@Service
public class GenerationExecutionWorkspaceService {

    static final String EXECUTION_ROOT_NAME = ".generation-executions";
    private static final String READY_MARKER_NAME = ".workspace-ready";
    private static final String WORKSPACE_DIRECTORY_NAME = "workspace";

    private final CodeStorageProperties storageProperties;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationWorkspaceExecutionScope executionScope;
    private final ArtifactDirectoryCopier artifactDirectoryCopier;
    private final GenerationExecutionContextService executionContextService;
    private final ArtifactLifecycleProperties artifactLifecycleProperties;

    public GenerationExecutionWorkspaceService(
            CodeStorageProperties storageProperties,
            GenerationWorkspaceService generationWorkspaceService,
            GenerationWorkspaceExecutionScope executionScope,
            ArtifactDirectoryCopier artifactDirectoryCopier,
            GenerationExecutionContextService executionContextService,
            ArtifactLifecycleProperties artifactLifecycleProperties
    ) {
        this.storageProperties = Objects.requireNonNull(storageProperties, "storageProperties");
        this.generationWorkspaceService = Objects.requireNonNull(
                generationWorkspaceService, "generationWorkspaceService");
        this.executionScope = Objects.requireNonNull(executionScope, "executionScope");
        this.artifactDirectoryCopier = Objects.requireNonNull(
                artifactDirectoryCopier, "artifactDirectoryCopier");
        this.executionContextService = Objects.requireNonNull(
                executionContextService, "executionContextService");
        this.artifactLifecycleProperties = Objects.requireNonNull(
                artifactLifecycleProperties, "artifactLifecycleProperties");
    }

    /** Registers one fence and eagerly materializes its persisted command workspace. */
    public GenerationExecutionWorkspace register(GenerationExecutionFence fence,
                                                 Long appId,
                                                 CodeGenTypeEnum baseCodeGenType) {
        requireIdentity(fence, appId, baseCodeGenType);
        executionScope.register(
                fence,
                appId,
                baseCodeGenType,
                requestedType -> materialize(
                        fence,
                        appId,
                        requestedType,
                        requestedType == baseCodeGenType
                )
        );
        return executionScope.require(fence, appId, baseCodeGenType);
    }

    /** Resolves or lazily materializes another project type owned by the same execution fence. */
    public GenerationExecutionWorkspace require(GenerationExecutionFence fence,
                                                Long appId,
                                                CodeGenTypeEnum codeGenType) {
        return executionScope.require(fence, appId, codeGenType);
    }

    public Optional<GenerationExecutionWorkspace> find(GenerationExecutionFence fence,
                                                       Long appId,
                                                       CodeGenTypeEnum codeGenType) {
        return executionScope.find(fence, appId, codeGenType);
    }

    public void clear(GenerationExecutionFence fence) {
        executionScope.clear(fence);
    }

    private GenerationExecutionWorkspace materialize(GenerationExecutionFence fence,
                                                     Long appId,
                                                     CodeGenTypeEnum codeGenType,
                                                     boolean seedCanonicalWorkspace) {
        String taskId = fence.taskId();
        Path partialTypeRoot = null;
        try {
            executionContextService.assertCanContinue(taskId);
            Path outputRoot = prepareOutputRoot();
            Path executionRoot = ensureDirectChild(outputRoot, EXECUTION_ROOT_NAME);
            Path appRoot = ensureDirectChild(executionRoot, "app-" + appId);
            Path taskRoot = ensureDirectChild(appRoot, fence.taskId());
            Path epochRoot = ensureDirectChild(taskRoot, "epoch-" + fence.executionEpoch());
            Path typeRoot = ensureDirectChild(epochRoot, codeGenType.getValue());
            Path readyMarker = typeRoot.resolve(READY_MARKER_NAME).normalize();
            Path workspaceRoot = typeRoot.resolve(WORKSPACE_DIRECTORY_NAME).normalize();
            ensureDirectChildPath(typeRoot, workspaceRoot);

            if (Files.exists(readyMarker, LinkOption.NOFOLLOW_LINKS)) {
                validateRegularFile(readyMarker, "execution workspace readiness marker is unsafe");
                ReadyMetadata metadata = readReadyMetadata(readyMarker);
                executionContextService.assertCanContinue(taskId);
                GenerationWorkspace existing = generationWorkspaceService.resolveExecutionWorkspace(
                        appId, codeGenType, workspaceRoot, metadata.containsProject());
                return new GenerationExecutionWorkspace(
                        appId, fence, codeGenType, epochRoot, typeRoot, existing,
                        metadata.seededFromEpoch()
                );
            }

            partialTypeRoot = typeRoot;
            deleteTreeIfExists(workspaceRoot, taskId);
            executionContextService.assertCanContinue(taskId);
            SeedWorkspace seed = findPreviousEpochWorkspace(
                    taskRoot, fence.executionEpoch(), codeGenType, taskId)
                    .orElseGet(() -> seedCanonicalWorkspace
                            ? canonicalSeed(appId, codeGenType)
                            : SeedWorkspace.empty());
            executionContextService.assertCanContinue(taskId);
            if (seed.path() == null) {
                Files.createDirectory(workspaceRoot);
            } else {
                Duration copyTimeout = executionContextService.clampTimeout(
                        taskId,
                        artifactLifecycleProperties.getExecutionWorkspaceCopyTimeout()
                );
                artifactDirectoryCopier.copyExecutionWorkspace(
                        seed.path(),
                        workspaceRoot,
                        copyTimeout,
                        () -> executionContextService.shouldStop(taskId)
                );
            }
            executionContextService.assertCanContinue(taskId);
            writeReadyMarker(readyMarker, seed);
            executionContextService.assertCanContinue(taskId);
            GenerationWorkspace workspace = generationWorkspaceService.resolveExecutionWorkspace(
                    appId, codeGenType, workspaceRoot, seed.containsProject());
            return new GenerationExecutionWorkspace(
                    appId, fence, codeGenType, epochRoot, typeRoot, workspace, seed.executionEpoch());
        } catch (GenerationExecutionPolicyException exception) {
            cleanupPartialMaterialization(partialTypeRoot, exception);
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cleanupPartialMaterialization(partialTypeRoot, exception);
            executionContextService.assertCanContinue(taskId);
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "Execution workspace materialization was interrupted",
                    exception
            );
        } catch (BusinessException exception) {
            cleanupPartialMaterialization(partialTypeRoot, exception);
            throw exception;
        } catch (ArtifactCopyException exception) {
            if (exception.reason() == ArtifactCopyException.Reason.INTERRUPTED) {
                Thread.currentThread().interrupt();
            }
            cleanupPartialMaterialization(partialTypeRoot, exception);
            if (isPolicyControlledCopyStop(exception.reason())) {
                executionContextService.assertCanContinue(taskId);
            }
            ErrorCode errorCode = exception.reason() == ArtifactCopyException.Reason.UNSAFE_SYMBOLIC_LINK
                    ? ErrorCode.NO_AUTH_ERROR
                    : ErrorCode.OPERATION_ERROR;
            throw new BusinessException(errorCode, "Failed to seed the execution workspace", exception);
        } catch (IOException | SecurityException exception) {
            cleanupPartialMaterialization(partialTypeRoot, exception);
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "Failed to prepare the execution workspace",
                    exception
            );
        }
    }

    private Path prepareOutputRoot() throws IOException {
        Path configured = storageProperties.outputRoot();
        Files.createDirectories(configured);
        validateDirectory(configured, "generated-code output root is unsafe");
        return configured.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private SeedWorkspace canonicalSeed(Long appId, CodeGenTypeEnum codeGenType) {
        GenerationWorkspace canonical = generationWorkspaceService.resolveCanonical(appId, codeGenType);
        return canonical.exists()
                ? new SeedWorkspace(canonical.canonicalRootPath(), null, true)
                : SeedWorkspace.empty();
    }

    private Optional<SeedWorkspace> findPreviousEpochWorkspace(Path taskRoot,
                                                               long currentEpoch,
                                                               CodeGenTypeEnum codeGenType,
                                                               String taskId)
            throws IOException {
        if (!Files.isDirectory(taskRoot, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try (Stream<Path> children = Files.list(taskRoot)) {
            return children
                    .peek(ignored -> executionContextService.assertCanContinue(taskId))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> new EpochDirectory(path, parseEpoch(path)))
                    .filter(candidate -> candidate.epoch() > 0 && candidate.epoch() < currentEpoch)
                    .sorted(Comparator.comparingLong(EpochDirectory::epoch).reversed())
                    .map(candidate -> previousWorkspace(candidate, codeGenType))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .findFirst();
        }
    }

    private Optional<SeedWorkspace> previousWorkspace(EpochDirectory candidate,
                                                      CodeGenTypeEnum codeGenType) {
        Path typeRoot = candidate.path().resolve(codeGenType.getValue()).normalize();
        Path readyMarker = typeRoot.resolve(READY_MARKER_NAME).normalize();
        Path workspaceRoot = typeRoot.resolve(WORKSPACE_DIRECTORY_NAME).normalize();
        try {
            if (!Files.isRegularFile(readyMarker, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(workspaceRoot)) {
                return Optional.empty();
            }
            return Optional.of(new SeedWorkspace(
                    workspaceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS),
                    candidate.epoch(),
                    true
            ));
        } catch (IOException | SecurityException exception) {
            log.warn("Ignoring unavailable previous execution workspace, path: {}", workspaceRoot);
            return Optional.empty();
        }
    }

    private long parseEpoch(Path path) {
        Path fileName = path == null ? null : path.getFileName();
        if (fileName == null || !fileName.toString().startsWith("epoch-")) {
            return -1L;
        }
        try {
            return Long.parseLong(fileName.toString().substring("epoch-".length()));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private Path ensureDirectChild(Path parent, String childName) throws IOException {
        if (childName == null || childName.isBlank()
                || childName.contains("/") || childName.contains("\\")
                || ".".equals(childName) || "..".equals(childName)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "execution workspace path segment is invalid");
        }
        Path child = parent.resolve(childName).normalize();
        ensureDirectChildPath(parent, child);
        if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(child);
        }
        validateDirectory(child, "execution workspace directory is unsafe");
        return child.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private void ensureDirectChildPath(Path parent, Path child) {
        if (child.getParent() == null || !child.getParent().equals(parent) || !child.startsWith(parent)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "execution workspace path escaped its parent");
        }
    }

    private void validateDirectory(Path directory, String message) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, message);
        }
    }

    private void validateRegularFile(Path file, String message) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, message);
        }
    }

    private void writeReadyMarker(Path readyMarker, SeedWorkspace seed) throws IOException {
        String content = "containsProject=" + seed.containsProject() + '\n'
                + "seededFromEpoch=" + (seed.executionEpoch() == null ? "" : seed.executionEpoch()) + '\n';
        Files.writeString(
                readyMarker,
                content,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
    }

    private ReadyMetadata readReadyMetadata(Path readyMarker) {
        try {
            String value = Files.readString(readyMarker).trim();
            if (value.isEmpty() || "canonical-or-empty".equals(value)) {
                return new ReadyMetadata(null, false);
            }
            boolean containsProject = false;
            Long seededFromEpoch = null;
            for (String line : value.split("\\R")) {
                String[] pair = line.split("=", 2);
                if (pair.length != 2) {
                    continue;
                }
                if ("containsProject".equals(pair[0])) {
                    containsProject = Boolean.parseBoolean(pair[1].trim());
                } else if ("seededFromEpoch".equals(pair[0]) && !pair[1].isBlank()) {
                    long epoch = Long.parseLong(pair[1].trim());
                    seededFromEpoch = epoch > 0 ? epoch : null;
                }
            }
            return new ReadyMetadata(seededFromEpoch, containsProject);
        } catch (IOException | NumberFormatException ignored) {
            return new ReadyMetadata(null, false);
        }
    }

    private void deleteTreeIfExists(Path root) throws IOException {
        deleteTreeIfExists(root, null);
    }

    private void deleteTreeIfExists(Path root, String taskId) throws IOException {
        assertCanContinueIfManaged(taskId);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(root);
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                assertCanContinueIfManaged(taskId);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                assertCanContinueIfManaged(taskId);
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void assertCanContinueIfManaged(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            executionContextService.assertCanContinue(taskId);
        }
    }

    private boolean isPolicyControlledCopyStop(ArtifactCopyException.Reason reason) {
        return reason == ArtifactCopyException.Reason.CANCELLED
                || reason == ArtifactCopyException.Reason.TIMED_OUT
                || reason == ArtifactCopyException.Reason.INTERRUPTED;
    }

    private void cleanupPartialMaterialization(Path partialTypeRoot, Throwable primaryFailure) {
        if (partialTypeRoot == null) {
            return;
        }
        try {
            deleteTreeIfExists(partialTypeRoot);
        } catch (IOException | RuntimeException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    private void requireIdentity(GenerationExecutionFence fence,
                                 Long appId,
                                 CodeGenTypeEnum codeGenType) {
        Objects.requireNonNull(fence, "fence");
        if (appId == null || appId <= 0 || codeGenType == null) {
            throw new IllegalArgumentException("execution workspace identity is incomplete");
        }
    }

    private record EpochDirectory(Path path, long epoch) {
    }

    private record SeedWorkspace(Path path, Long executionEpoch, boolean containsProject) {
        private static SeedWorkspace empty() {
            return new SeedWorkspace(null, null, false);
        }
    }

    private record ReadyMetadata(Long seededFromEpoch, boolean containsProject) {
    }
}
