package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchExecutionPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsShouldBeValid() {
        assertTrue(validator.validate(new PatchExecutionProperties()).isEmpty());
    }

    @Test
    void shouldRejectTotalContentBudgetBelowPerOperationBudget() {
        PatchExecutionProperties properties = new PatchExecutionProperties();
        properties.setMaxOperationContentChars(4_096);
        properties.setMaxTotalContentChars(2_048);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectNonPositiveOperationLimit() {
        PatchExecutionProperties properties = new PatchExecutionProperties();
        properties.setMaxOperations(0);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectRollbackBudgetBelowReadableFileLimit() {
        PatchExecutionProperties properties = new PatchExecutionProperties();
        properties.setMaxReadableFileBytes(4_096);
        properties.setMaxRollbackSnapshotBytes(2_048);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
