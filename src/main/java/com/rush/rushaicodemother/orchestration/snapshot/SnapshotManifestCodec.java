package com.rush.rushaicodemother.orchestration.snapshot;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/** 严格、有限 schema 的 manifest 编解码器。 */
final class SnapshotManifestCodec {

    static final String MANIFEST_FILE = "manifest.json";
    static final String PAYLOAD_DIRECTORY = "payload";
    static final long MAX_MANIFEST_BYTES = 32L * 1024L;

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final HexFormat HEX_FORMAT = HexFormat.of();
    private final ObjectMapper objectMapper;

    SnapshotManifestCodec() {
        objectMapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
    }

    EncodedManifest encode(SnapshotManifest manifest) throws SnapshotStoreException {
        validate(manifest);
        try {
            String json = objectMapper.writeValueAsString(manifest);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES) {
                throw invalid("snapshot manifest exceeds size limit", null);
            }
            return new EncodedManifest(json, sha256(json));
        } catch (SnapshotStoreException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("failed to encode snapshot manifest", exception);
        }
    }

    DecodedManifest decode(String json) throws SnapshotStoreException {
        if (json == null || json.isBlank()
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES) {
            throw invalid("snapshot manifest is empty or exceeds size limit", null);
        }
        try {
            SnapshotManifest manifest = objectMapper.readValue(json, SnapshotManifest.class);
            validate(manifest);
            return new DecodedManifest(manifest, sha256(json));
        } catch (SnapshotStoreException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("snapshot manifest is malformed", exception);
        }
    }

    private void validate(SnapshotManifest manifest) throws SnapshotStoreException {
        if (manifest == null) {
            throw invalid("snapshot manifest must not be null", null);
        }
        if (!SnapshotManifest.CURRENT_SCHEMA_VERSION.equals(manifest.schemaVersion())) {
            throw new SnapshotStoreException(
                    SnapshotStoreException.Reason.UNSUPPORTED_SCHEMA,
                    "unsupported snapshot manifest schema"
            );
        }
        if (!SnapshotManifest.CURRENT_COPY_POLICY_VERSION.equals(manifest.copyPolicy())) {
            throw new SnapshotStoreException(
                    SnapshotStoreException.Reason.UNSUPPORTED_SCHEMA,
                    "unsupported snapshot copy policy"
            );
        }
        try {
            UUID.fromString(requireText(manifest.snapshotId(), "snapshotId"));
            requireText(manifest.snapshotName(), "snapshotName");
            if (manifest.appId() <= 0) {
                throw new IllegalArgumentException("appId must be positive");
            }
            requireText(manifest.codeGenType(), "codeGenType");
            SnapshotScope.normalizeRelativePath(manifest.scope());
            SnapshotKind.fromValue(requireText(manifest.kind(), "kind"));
            requireText(manifest.taskId(), "taskId");
            if (manifest.executionEpoch() <= 0) {
                throw new IllegalArgumentException("executionEpoch must be positive");
            }
            if (!SHA256_HEX.matcher(requireText(manifest.treeHash(), "treeHash")).matches()) {
                throw new IllegalArgumentException("treeHash must be lowercase SHA-256");
            }
            if (manifest.fileCount() < 0 || manifest.byteCount() < 0) {
                throw new IllegalArgumentException("snapshot counters must not be negative");
            }
            Instant.parse(requireText(manifest.createdAt(), "createdAt"));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw invalid("snapshot manifest violates its schema", exception);
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(fieldName + " must be non-blank canonical text");
        }
        return value;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX_FORMAT.formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not support SHA-256", exception);
        }
    }

    private SnapshotStoreException invalid(String message, Throwable cause) {
        return cause == null
                ? new SnapshotStoreException(SnapshotStoreException.Reason.MANIFEST_INVALID, message)
                : new SnapshotStoreException(SnapshotStoreException.Reason.MANIFEST_INVALID, message, cause);
    }

    record EncodedManifest(String json, String sha256) {
    }

    record DecodedManifest(SnapshotManifest manifest, String sha256) {
    }
}
