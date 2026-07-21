-- Replace plaintext AI provider credentials with envelope-encrypted references.
-- Existing values are intentionally preserved in secretRef and are encrypted by the
-- fail-closed application startup migrator before readiness is published.
ALTER TABLE ai_model
    CHANGE COLUMN apiKey secretRef varchar(4096) null
        comment 'Envelope-encrypted API credential reference',
    ADD COLUMN secretFingerprint char(64) null
        comment 'Stable HMAC-SHA256 credential fingerprint' AFTER secretRef,
    ADD COLUMN secretKeyId varchar(64) null
        comment 'Key-encryption-key identifier' AFTER secretFingerprint;
