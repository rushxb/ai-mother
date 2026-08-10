package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerRuntimePropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptDefaultRuntimeLimits() {
        assertTrue(validator.validate(new DevServerRuntimeProperties()).isEmpty());
    }

    @Test
    void shouldRejectInvalidDurationsAndPollingRelationship() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setValidationErrorCollectionWindow(Duration.ZERO);
        properties.setStartupTimeout(Duration.ofMillis(100));
        properties.setReadinessPollInterval(Duration.ofMillis(100));

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectInvalidValidationPollingInterval() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setValidationPollInterval(Duration.ZERO);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectCriticalDrainWindowLongerThanFullCollectionWindow() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setValidationErrorCollectionWindow(Duration.ofMillis(100));
        properties.setValidationCriticalErrorDrainWindow(Duration.ofMillis(101));

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectHeartbeatThatCannotRenewBeforeLeaseExpiration() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setLeaseDuration(Duration.ofSeconds(10));
        properties.setHeartbeatInterval(Duration.ofSeconds(10));

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectExcessiveRuntimeDuration() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setStartupTimeout(Duration.ofHours(2));

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectInvalidPortRange() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setPortRangeStart(20000);
        properties.setPortRangeEnd(10000);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectUnboundedResourceLimits() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setMaxServersPerUser(101);
        properties.setMaxOutputLineLength(100_001);
        properties.setMaxRecentOutputLines(10_001);

        assertFalse(validator.validate(properties).isEmpty());
    }

    /** 空闲判定由心跳巡检推进，短于心跳间隔时一次巡检就可能误杀刚建立的会话。 */
    @Test
    void shouldRejectIdleTimeoutShorterThanTheHeartbeatThatEvaluatesIt() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setIdleSessionTimeout(Duration.ofSeconds(5));
        properties.setHeartbeatInterval(Duration.ofSeconds(10));

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectIdleTimeoutBeyondTheRuntimeUpperBound() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setIdleSessionTimeout(Duration.ofHours(2));

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectNodeIdentityThatCannotBeSafelyRouted() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setNodeId("preview-node/../../admin");

        assertFalse(validator.validate(properties).isEmpty());
    }
}
