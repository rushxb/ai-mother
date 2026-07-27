package com.rush.rushaicodemother.memory;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.utility.response.CheckHealthResp;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component("milvusMemory")
@ConditionalOnProperty(prefix = "app.memory.long-term", name = "enabled",
        havingValue = "true")
/**
 * Milvus 记忆健康指示器。
 */
public class MilvusMemoryHealthIndicator implements HealthIndicator {
    private final MilvusClientV2 client;
    private final MilvusMemoryCollectionManager collectionManager;

    public MilvusMemoryHealthIndicator(MilvusClientV2 client,
                                       MilvusMemoryCollectionManager collectionManager) {
        this.client = client;
        this.collectionManager = collectionManager;
    }

    @Override
    public Health health() {
        try {
            CheckHealthResp response = client.checkHealth();
            if (response != null && Boolean.TRUE.equals(response.getIsHealthy())) {
                collectionManager.ensureReady();
                return Health.up()
                        .withDetail("backend", "milvus")
                        .withDetails(collectionManager.readinessDetails())
                        .build();
            }
            collectionManager.invalidate();
            return Health.down()
                    .withDetail("backend", "milvus")
                    .withDetail("reasons", response == null ? java.util.List.of("empty_response") : response.getReasons())
                    .build();
        } catch (RuntimeException failure) {
            collectionManager.invalidate();
            return Health.down(failure).withDetail("backend", "milvus").build();
        }
    }
}
