package com.rush.rushaicodemother.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilvusMemoryPropertiesTest {

    @Test
    void defaultsMustNotConnectToAnExternalMilvusImplicitly() {
        MilvusMemoryProperties properties = new MilvusMemoryProperties();

        assertFalse(properties.isEnabled());
        assertEquals("", properties.getUri());
        assertEquals("generation_memory_v2", properties.getCollectionName());
        assertTrue(properties.isConfigurationValid());
    }

    @Test
    void enabledMemoryMustRequireEndpointAndConfiguredAuthenticationPolicy() {
        MilvusMemoryProperties properties = new MilvusMemoryProperties();
        properties.setEnabled(true);

        assertFalse(properties.isConfigurationValid());

        properties.setUri("http://milvus.internal:19530");
        assertTrue(properties.isConfigurationValid());

        properties.setAuthenticationRequired(true);
        assertFalse(properties.isConfigurationValid());

        properties.setToken("user:a-production-secret");
        assertTrue(properties.isConfigurationValid());
    }

    @Test
    void tlsPolicyMustRejectPlaintextEndpoints() {
        MilvusMemoryProperties properties = new MilvusMemoryProperties();
        properties.setEnabled(true);
        properties.setTlsRequired(true);
        properties.setUri("http://milvus.internal:19530");

        assertFalse(properties.isConfigurationValid());

        properties.setUri("https://milvus.internal:19530");
        assertTrue(properties.isConfigurationValid());
    }
}
