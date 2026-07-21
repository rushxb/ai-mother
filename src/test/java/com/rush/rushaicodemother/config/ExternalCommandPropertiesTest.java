package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalCommandPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptDefaultGenerationCommitLimits() {
        assertTrue(validator.validate(new GenerationCommitProperties()).isEmpty());
    }

    @Test
    void shouldRejectUnsafeGenerationCommitHeartbeat() {
        GenerationCommitProperties properties = new GenerationCommitProperties();
        properties.setHeartbeatInterval(properties.getCommandTimeout());

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectGenerationCommitLockStripeOutsideSupportedRange() {
        GenerationCommitProperties tooSmall = new GenerationCommitProperties();
        tooSmall.setLockStripes(0);
        GenerationCommitProperties tooLarge = new GenerationCommitProperties();
        tooLarge.setLockStripes(1025);

        assertFalse(validator.validate(tooSmall).isEmpty());
        assertFalse(validator.validate(tooLarge).isEmpty());
    }

    @Test
    void shouldAcceptDefaultArtifactLifecycleLimits() {
        assertTrue(validator.validate(new ArtifactLifecycleProperties()).isEmpty());
    }

    @Test
    void shouldRejectNonPositiveArtifactCopyTimeout() {
        ArtifactLifecycleProperties properties = new ArtifactLifecycleProperties();
        properties.setCopyTimeout(Duration.ZERO);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectNonPositiveExecutionWorkspaceCopyTimeout() {
        ArtifactLifecycleProperties properties = new ArtifactLifecycleProperties();
        properties.setExecutionWorkspaceCopyTimeout(Duration.ZERO);

        assertFalse(validator.validate(properties).isEmpty());
    }
    @Test
    void shouldRejectArtifactCopyCountsAndDepthOutsideSupportedRange() {
        ArtifactLifecycleProperties invalidFiles = new ArtifactLifecycleProperties();
        invalidFiles.setMaxFiles(0);
        ArtifactLifecycleProperties invalidDirectories = new ArtifactLifecycleProperties();
        invalidDirectories.setMaxDirectories(100_001);
        ArtifactLifecycleProperties invalidDepth = new ArtifactLifecycleProperties();
        invalidDepth.setMaxDirectoryDepth(257);

        assertFalse(validator.validate(invalidFiles).isEmpty());
        assertFalse(validator.validate(invalidDirectories).isEmpty());
        assertFalse(validator.validate(invalidDepth).isEmpty());
    }

    @Test
    void shouldRejectArtifactTotalBytesBelowSingleFileLimit() {
        ArtifactLifecycleProperties properties = new ArtifactLifecycleProperties();
        properties.setMaxFileBytes(2_000_000);
        properties.setMaxTotalBytes(1_048_576);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectArtifactPublishRetrySettingsOutsideSupportedRange() {
        ArtifactLifecycleProperties invalidAttempts = new ArtifactLifecycleProperties();
        invalidAttempts.setPublishMaxAttempts(0);
        ArtifactLifecycleProperties invalidDelay = new ArtifactLifecycleProperties();
        invalidDelay.setPublishRetryDelayMillis(5_001);

        assertFalse(validator.validate(invalidAttempts).isEmpty());
        assertFalse(validator.validate(invalidDelay).isEmpty());
    }
}
