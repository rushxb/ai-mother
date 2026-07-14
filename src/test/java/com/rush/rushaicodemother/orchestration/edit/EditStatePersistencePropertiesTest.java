package com.rush.rushaicodemother.orchestration.edit;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class EditStatePersistencePropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsMustRemainPositiveAndBounded() {
        EditStatePersistenceProperties properties = new EditStatePersistenceProperties();

        assertThat(validator.validate(properties)).isEmpty();
        assertThat(properties.getRootDirectory()).isNotNull();
        assertThat(properties.getMaxCacheEntries()).isEqualTo(1_000);
        assertThat(properties.getMaxPersistedApps()).isEqualTo(10_000);
        assertThat(properties.getMaxStateFileBytes()).isEqualTo(1024 * 1024);
        assertThat(properties.getCacheExpireAfterAccess()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.getStateRetention()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void invalidCapacityAndDurationCombinationsMustBeRejected() {
        EditStatePersistenceProperties properties = new EditStatePersistenceProperties();
        properties.setRootDirectory(null);
        properties.setMaxStateFileBytes(1_024);
        properties.setCacheExpireAfterAccess(Duration.ofDays(2));
        properties.setStateRetention(Duration.ofDays(1));

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("maxStateFileBytes", "storageConfigurationValid");
    }
}
