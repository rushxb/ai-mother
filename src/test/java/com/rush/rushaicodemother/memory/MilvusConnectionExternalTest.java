package com.rush.rushaicodemother.memory;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("external")
class MilvusConnectionExternalTest {

    @Test
    void configuredMilvusEndpointMustBeHealthy() {
        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder()
                .uri(System.getProperty("milvusUri", "http://192.168.20.138:19530"))
                .connectTimeoutMs(3_000)
                .rpcDeadlineMs(5_000)
                .enablePrecheck(false)
                .build());
        try {
            assertTrue(Boolean.TRUE.equals(client.checkHealth().getIsHealthy()));
        } finally {
            client.close();
        }
    }
}
