package com.rush.rushaicodemother.service.aimodel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * 在应用程序准备就绪之前删除旧的纯文本提供商凭据。
 *
 * <p>E每一行被替换为比较和设置语义。并发节点可以安全地竞争：
 * 丢失节点仅接受已受保护且可解析的行，否则启动
 * 关闭失败。逻辑删除的行保留的凭证将被删除而不是迁移。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiModelSecretMigrationRunner implements ApplicationRunner {

    private static final int BATCH_SIZE = 100;
    private static final String FINGERPRINT_PATTERN = "[a-f0-9]{64}";

    private final AiModelSecretMigrationRepository repository;
    private final AiModelSecretService secretService;

    @Override
    public void run(ApplicationArguments args) {
        long afterId = 0L;
        int migrated = 0;
        int cleared = 0;
        int verified = 0;

        while (true) {
            List<AiModelSecretMigrationRecord> batch = repository.findBatchAfter(afterId, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (AiModelSecretMigrationRecord record : batch) {
                if (record.modelId() <= afterId) {
                    throw migrationFailure(record.modelId(), "migration cursor did not advance");
                }
                afterId = record.modelId();
                if (record.deleted()) {
                    if (clearDeletedSecret(record)) {
                        cleared++;
                    } else {
                        verified++;
                    }
                } else if (secretService.isProtectedReference(record.secretRef())) {
                    requireValidProtectedRecord(record);
                    verified++;
                } else {
                    if (migrateLegacySecret(record)) {
                        migrated++;
                    } else {
                        verified++;
                    }
                }
            }
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }

        if (migrated > 0 || cleared > 0) {
            log.info("AI model secret migration completed: migrated={}, clearedDeleted={}, verified={}",
                    migrated, cleared, verified);
        }
    }

    private boolean migrateLegacySecret(AiModelSecretMigrationRecord record) {
        AiModelProtectedSecret protectedSecret = secretService.protect(record.secretRef());
        int affectedRows = repository.replaceIfCurrent(
                record.modelId(), sha256(record.secretRef()), protectedSecret);
        if (affectedRows == 1) {
            return true;
        }
        if (affectedRows != 0) {
            throw migrationFailure(record.modelId(), "unexpected replacement row count");
        }
        requireSafeConcurrentResult(record.modelId());
        return false;
    }

    private boolean clearDeletedSecret(AiModelSecretMigrationRecord record) {
        int affectedRows = repository.clearDeleted(record.modelId());
        if (affectedRows == 1) {
            return true;
        }
        if (affectedRows != 0) {
            throw migrationFailure(record.modelId(), "unexpected deletion cleanup row count");
        }
        AiModelSecretMigrationRecord current = repository.findById(record.modelId());
        if (current != null && current.deleted() && isBlank(current.secretRef())
                && isBlank(current.secretFingerprint()) && isBlank(current.secretKeyId())) {
            return false;
        }
        throw migrationFailure(record.modelId(), "deleted secret cleanup lost an unsafe race");
    }

    private void requireSafeConcurrentResult(long modelId) {
        AiModelSecretMigrationRecord current = repository.findById(modelId);
        if (current == null) {
            throw migrationFailure(modelId, "model row disappeared during migration");
        }
        if (current.deleted() && isBlank(current.secretRef())
                && isBlank(current.secretFingerprint()) && isBlank(current.secretKeyId())) {
            return;
        }
        requireValidProtectedRecord(current);
    }

    private void requireValidProtectedRecord(AiModelSecretMigrationRecord record) {
        String fingerprint = record.secretFingerprint();
        String keyId = record.secretKeyId();
        String reference = record.secretRef();
        if (isBlank(fingerprint)
                || !fingerprint.matches(FINGERPRINT_PATTERN)
                || isBlank(keyId)
                || !secretService.isProtectedReference(reference)
                || !keyId.equals(secretService.keyId(reference))
                || !secretService.canResolve(reference)) {
            throw migrationFailure(record.modelId(), "protected secret metadata is invalid");
        }
    }

    private IllegalStateException migrationFailure(long modelId, String reason) {
        return new IllegalStateException(
                "AI model secret migration failed for model id " + modelId + ": " + reason);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String sha256(String value) {
        byte[] input = value.getBytes(StandardCharsets.UTF_8);
        byte[] digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(input);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } finally {
            Arrays.fill(input, (byte) 0);
            if (digest != null) {
                Arrays.fill(digest, (byte) 0);
            }
        }
    }
}
