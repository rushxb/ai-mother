package com.rush.rushaicodemother.infrastructure.process;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeProcessEnvironmentTest {

    @Test
    void generatedNodeProcessMustRemoveNonAllowlistedServerEnvironment() {
        Set<String> variablesToRemove = NodeProcessEnvironment.variablesToRemove(Set.of(
                "Path",
                "SystemRoot",
                "TEMP",
                "OPENAI_API_KEY",
                "SPRING_DATASOURCE_PASSWORD",
                "AWS_SECRET_ACCESS_KEY",
                "CUSTOM_TENANT_SECRET"
        ));

        assertFalse(variablesToRemove.contains("Path"));
        assertFalse(variablesToRemove.contains("SystemRoot"));
        assertFalse(variablesToRemove.contains("TEMP"));
        assertTrue(variablesToRemove.contains("OPENAI_API_KEY"));
        assertTrue(variablesToRemove.contains("SPRING_DATASOURCE_PASSWORD"));
        assertTrue(variablesToRemove.contains("AWS_SECRET_ACCESS_KEY"));
        assertTrue(variablesToRemove.contains("CUSTOM_TENANT_SECRET"));
    }
}
