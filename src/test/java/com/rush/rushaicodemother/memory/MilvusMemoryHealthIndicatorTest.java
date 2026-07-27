package com.rush.rushaicodemother.memory;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.utility.response.CheckHealthResp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusMemoryHealthIndicatorTest {

    @Test
    void healthyBackendMustAlsoVerifyTheVersionedCollectionContract() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        MilvusMemoryCollectionManager collectionManager =
                mock(MilvusMemoryCollectionManager.class);
        CheckHealthResp response = mock(CheckHealthResp.class);
        when(response.getIsHealthy()).thenReturn(true);
        when(client.checkHealth()).thenReturn(response);
        when(collectionManager.readinessDetails()).thenReturn(Map.of(
                "collection", "generation_memory_v2", "ready", true));
        MilvusMemoryHealthIndicator indicator =
                new MilvusMemoryHealthIndicator(client, collectionManager);

        var health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("generation_memory_v2", health.getDetails().get("collection"));
        verify(collectionManager).ensureReady();
        verify(collectionManager, never()).invalidate();
    }

    @Test
    void unhealthyBackendMustInvalidateCachedReadiness() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        MilvusMemoryCollectionManager collectionManager =
                mock(MilvusMemoryCollectionManager.class);
        CheckHealthResp response = mock(CheckHealthResp.class);
        when(response.getIsHealthy()).thenReturn(false);
        when(response.getReasons()).thenReturn(java.util.List.of("standby"));
        when(client.checkHealth()).thenReturn(response);
        MilvusMemoryHealthIndicator indicator =
                new MilvusMemoryHealthIndicator(client, collectionManager);

        var health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        verify(collectionManager).invalidate();
        verify(collectionManager, never()).ensureReady();
    }
}
