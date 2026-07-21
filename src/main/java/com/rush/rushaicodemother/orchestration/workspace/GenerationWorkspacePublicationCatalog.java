package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Filesystem catalog for versioned published workspaces.
 *
 * <p>Large directories are never swapped in place. Publication moves a completed execution
 * workspace to a unique version directory, then atomically replaces one small pointer file.</p>
 */
@Component
public class GenerationWorkspacePublicationCatalog {

    static final String PUBLISHED_ROOT_NAME = ".generation-published";
    static final String PUBLICATION_ROOT_NAME = ".generation-publications";
    static final String OWNER_MARKER_NAME = ".generation-publication-owner";
    static final int MAX_MANIFEST_BYTES = 4 * 1024;

    private static final Set<String> POINTER_FIELDS = Set.of(
            "schemaVersion", "appId", "codeGenType", "taskId", "executionEpoch", "publishedAt");

    private final CodeStorageProperties storageProperties;

    public GenerationWorkspacePublicationCatalog(CodeStorageProperties storageProperties) {
        this.storageProperties = Objects.requireNonNull(storageProperties, "storageProperties");
    }

    public Optional<GenerationWorkspacePublicationPointer> findCurrent(
            Long appId,
            CodeGenTypeEnum codeGenType
    ) {
        validateIdentity(appId, codeGenType);
        Path pointerPath = pointerPath(appId, codeGenType, false);
        if (!Files.exists(pointerPath, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            validateRegularFile(pointerPath, "publication pointer is unsafe");
            return Optional.of(parse(
                    readBoundedFile(pointerPath, "publication pointer is too large"),
                    appId,
                    codeGenType));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Published workspace pointer could not be read", exception);
        }
    }

    public Optional<Path> findCurrentWorkspace(Long appId, CodeGenTypeEnum codeGenType) {
        return findCurrent(appId, codeGenType).map(this::resolveWorkspace);
    }

    public Path resolveWorkspace(GenerationWorkspacePublicationPointer pointer) {
        Objects.requireNonNull(pointer, "pointer");
        try {
            Path workspace = versionWorkspacePath(pointer, false);
            validatePublishedVersion(workspace, pointer);
            Path realWorkspace = workspace.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path publishedRoot = publishedRoot(false);
            if (!realWorkspace.startsWith(publishedRoot)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                        "published workspace escaped its storage root");
            }
            return realWorkspace;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Published workspace could not be resolved", exception);
        }
    }

    /** Creates and returns the parent into which the final workspace directory will be moved. */
    public Path prepareVersionParent(GenerationWorkspacePublicationPointer pointer) {
        Objects.requireNonNull(pointer, "pointer");
        try {
            Path publishedRoot = publishedRoot(true);
            Path appRoot = ensureDirectChild(publishedRoot, "app-" + pointer.appId());
            Path taskRoot = ensureDirectChild(appRoot, pointer.taskId());
            Path epochRoot = ensureDirectChild(taskRoot, "epoch-" + pointer.executionEpoch());
            Path typeRoot = ensureDirectChild(epochRoot, pointer.codeGenType().getValue());
            return typeRoot;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Published workspace version directory could not be prepared", exception);
        }
    }

    public Path versionWorkspacePath(GenerationWorkspacePublicationPointer pointer) {
        return versionWorkspacePath(pointer, false);
    }

    public Path lockPath(Long appId) {
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
        try {
            Path publicationRoot = publicationRoot(true);
            Path appRoot = ensureDirectChild(publicationRoot, "app-" + appId);
            Path lockPath = appRoot.resolve("publication.lock").normalize();
            ensureDirectChildPath(appRoot, lockPath);
            return lockPath;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Publication lock path could not be prepared", exception);
        }
    }

    public PointerSnapshot snapshot(Long appId, CodeGenTypeEnum codeGenType) {
        Path pointerPath = pointerPath(appId, codeGenType, true);
        try {
            if (!Files.exists(pointerPath, LinkOption.NOFOLLOW_LINKS)) {
                return PointerSnapshot.absent();
            }
            validateRegularFile(pointerPath, "publication pointer is unsafe");
            return PointerSnapshot.present(readBoundedFile(
                    pointerPath, "publication pointer snapshot is too large"));
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Publication pointer snapshot failed", exception);
        }
    }

    public void activate(GenerationWorkspacePublicationPointer pointer) {
        Objects.requireNonNull(pointer, "pointer");
        Path pointerPath = pointerPath(pointer.appId(), pointer.codeGenType(), true);
        writeAtomically(pointerPath, serialize(pointer).getBytes(StandardCharsets.UTF_8));
    }

    public void writeOwnerMarker(Path workspace,
                                 GenerationWorkspacePublicationPointer pointer) {
        Objects.requireNonNull(pointer, "pointer");
        try {
            validateDirectory(workspace, "publication owner workspace is unsafe");
            Path marker = ownerMarkerPath(workspace);
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                validateRegularFile(marker, "publication owner marker is unsafe");
            }
            writeAtomically(marker, ownerMarker(pointer).getBytes(StandardCharsets.UTF_8));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Publication owner marker could not be written", exception);
        }
    }

    void validatePublishedVersion(Path workspace,
                                  GenerationWorkspacePublicationPointer pointer) throws IOException {
        Objects.requireNonNull(pointer, "pointer");
        validateDirectory(workspace, "published workspace version is unsafe");
        Path marker = ownerMarkerPath(workspace);
        validateRegularFile(marker, "publication owner marker is unsafe");
        String actualMarker = new String(
                readBoundedFile(marker, "publication owner marker is too large"),
                StandardCharsets.UTF_8);
        if (!ownerMarker(pointer).equals(actualMarker)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "published workspace owner marker does not match the execution fence");
        }
    }

    public void restore(Long appId,
                        CodeGenTypeEnum codeGenType,
                        PointerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Path pointerPath = pointerPath(appId, codeGenType, true);
        try {
            if (!snapshot.existed()) {
                if (Files.exists(pointerPath, LinkOption.NOFOLLOW_LINKS)) {
                    validateRegularFile(pointerPath, "publication pointer is unsafe");
                    Files.delete(pointerPath);
                }
                return;
            }
            writeAtomically(pointerPath, snapshot.content());
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Publication pointer rollback failed", exception);
        }
    }

    private Path versionWorkspacePath(GenerationWorkspacePublicationPointer pointer,
                                      boolean prepareParents) {
        Path typeRoot = prepareParents
                ? prepareVersionParent(pointer)
                : publishedRoot(false)
                .resolve("app-" + pointer.appId())
                .resolve(pointer.taskId())
                .resolve("epoch-" + pointer.executionEpoch())
                .resolve(pointer.codeGenType().getValue())
                .normalize();
        Path workspace = typeRoot.resolve("workspace").normalize();
        ensureDirectChildPath(typeRoot, workspace);
        return workspace;
    }

    private Path pointerPath(Long appId, CodeGenTypeEnum codeGenType, boolean prepareParent) {
        validateIdentity(appId, codeGenType);
        try {
            Path publicationRoot = publicationRoot(prepareParent);
            Path appRoot = publicationRoot.resolve("app-" + appId).normalize();
            ensureDirectChildPath(publicationRoot, appRoot);
            if (prepareParent) {
                appRoot = ensureDirectChild(publicationRoot, "app-" + appId);
            }
            Path pointer = appRoot.resolve(codeGenType.getValue() + ".current").normalize();
            ensureDirectChildPath(appRoot, pointer);
            return pointer;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Publication pointer path could not be resolved", exception);
        }
    }

    private Path publishedRoot(boolean create) {
        return root(PUBLISHED_ROOT_NAME, create);
    }

    private Path publicationRoot(boolean create) {
        return root(PUBLICATION_ROOT_NAME, create);
    }

    private Path root(String childName, boolean create) {
        try {
            Path outputRoot = storageProperties.outputRoot();
            if (create) {
                Files.createDirectories(outputRoot);
            }
            if (!Files.exists(outputRoot, LinkOption.NOFOLLOW_LINKS)) {
                // Before the first generation there is no catalog yet. Read-only lookups must
                // report an empty catalog instead of turning normal first-use state into an
                // availability failure.
                Path child = outputRoot.resolve(childName).normalize();
                ensureDirectChildPath(outputRoot, child);
                return child;
            }
            if (!Files.isDirectory(outputRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "generated-code output root is unavailable");
            }
            validateDirectory(outputRoot, "generated-code output root is unsafe");
            Path realOutputRoot = outputRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path child = realOutputRoot.resolve(childName).normalize();
            ensureDirectChildPath(realOutputRoot, child);
            if (create && !Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(child);
            }
            if (Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                validateDirectory(child, "publication catalog root is unsafe");
                return child.toRealPath(LinkOption.NOFOLLOW_LINKS);
            }
            return child;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Publication catalog root could not be resolved", exception);
        }
    }

    private Path ensureDirectChild(Path parent, String childName) throws IOException {
        if (childName == null || childName.isBlank()
                || childName.contains("/") || childName.contains("\\")
                || ".".equals(childName) || "..".equals(childName)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    "publication path segment is invalid");
        }
        Path child = parent.resolve(childName).normalize();
        ensureDirectChildPath(parent, child);
        if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(child);
        }
        validateDirectory(child, "publication directory is unsafe");
        return child.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private void ensureDirectChildPath(Path parent, Path child) {
        if (child.getParent() == null || !child.getParent().equals(parent) || !child.startsWith(parent)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    "publication path escaped its parent");
        }
    }

    private void writeAtomically(Path target, byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_MANIFEST_BYTES) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "publication manifest content is invalid");
        }
        Path parent = target.getParent();
        if (parent == null) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    "publication pointer has no safe parent");
        }
        Path temporary = parent.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID()).normalize();
        ensureDirectChildPath(parent, temporary);
        try {
            Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Publication pointer atomic update failed", exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // A failed temporary-file cleanup is safe for correctness and handled by the janitor.
            }
        }
    }

    private String serialize(GenerationWorkspacePublicationPointer pointer) {
        return ownerMarker(pointer)
                + "publishedAt=" + pointer.publishedAt() + '\n';
    }

    private String ownerMarker(GenerationWorkspacePublicationPointer pointer) {
        return "schemaVersion=" + pointer.schemaVersion() + '\n'
                + "appId=" + pointer.appId() + '\n'
                + "codeGenType=" + pointer.codeGenType().getValue() + '\n'
                + "taskId=" + pointer.taskId() + '\n'
                + "executionEpoch=" + pointer.executionEpoch() + '\n';
    }

    private GenerationWorkspacePublicationPointer parse(byte[] content,
                                                        Long expectedAppId,
                                                        CodeGenTypeEnum expectedCodeGenType) {
        try {
            Map<String, String> properties = parseFields(
                    new String(content, StandardCharsets.UTF_8), POINTER_FIELDS);
            GenerationWorkspacePublicationPointer pointer = new GenerationWorkspacePublicationPointer(
                    Integer.parseInt(required(properties, "schemaVersion")),
                    Long.parseLong(required(properties, "appId")),
                    CodeGenTypeEnum.getEnumByValue(required(properties, "codeGenType")),
                    required(properties, "taskId"),
                    Long.parseLong(required(properties, "executionEpoch")),
                    Instant.parse(required(properties, "publishedAt"))
            );
            if (!Objects.equals(expectedAppId, pointer.appId())
                    || expectedCodeGenType != pointer.codeGenType()) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                        "publication pointer identity mismatch");
            }
            return pointer;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Publication pointer content is invalid", exception);
        }
    }

    private Map<String, String> parseFields(String value, Set<String> allowedFields) {
        Map<String, String> fields = new LinkedHashMap<>();
        String normalized = value.replace("\r\n", "\n");
        if (normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("publication manifest line ending is invalid");
        }
        for (String line : normalized.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0 || separator == line.length() - 1) {
                throw new IllegalArgumentException("publication manifest line is invalid");
            }
            String key = line.substring(0, separator);
            String fieldValue = line.substring(separator + 1).trim();
            if (!allowedFields.contains(key) || fieldValue.isEmpty()
                    || fields.putIfAbsent(key, fieldValue) != null) {
                throw new IllegalArgumentException("publication manifest field is invalid: " + key);
            }
        }
        if (fields.size() != allowedFields.size()) {
            throw new IllegalArgumentException("publication manifest fields are incomplete");
        }
        return fields;
    }

    private String required(Map<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("publication pointer field is missing: " + key);
        }
        return value.trim();
    }

    private Path ownerMarkerPath(Path workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("publication workspace is required");
        }
        Path marker = workspace.resolve(OWNER_MARKER_NAME).normalize();
        ensureDirectChildPath(workspace, marker);
        return marker;
    }

    private byte[] readBoundedFile(Path path, String limitMessage) throws IOException {
        try (InputStream input = Files.newInputStream(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] content = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (content.length == 0 || content.length > MAX_MANIFEST_BYTES) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, limitMessage);
            }
            return content;
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

    private void validateIdentity(Long appId, CodeGenTypeEnum codeGenType) {
        if (appId == null || appId <= 0 || codeGenType == null) {
            throw new IllegalArgumentException("publication identity is incomplete");
        }
    }

    public record PointerSnapshot(boolean existed, byte[] content) {
        public PointerSnapshot {
            content = content == null ? new byte[0] : content.clone();
            if (existed && (content.length == 0 || content.length > MAX_MANIFEST_BYTES)) {
                throw new IllegalArgumentException("existing pointer snapshot cannot be empty");
            }
        }

        public static PointerSnapshot absent() {
            return new PointerSnapshot(false, new byte[0]);
        }

        public static PointerSnapshot present(byte[] content) {
            return new PointerSnapshot(true, content);
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
