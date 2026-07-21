package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.memory.FailoverLongTermMemoryStore;
import com.rush.rushaicodemother.memory.InMemoryLongTermMemoryStore;
import com.rush.rushaicodemother.memory.LongTermMemoryStore;
import com.rush.rushaicodemother.memory.MilvusLongTermMemoryStore;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
public class MilvusMemoryConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.memory.long-term", name = "enabled",
            havingValue = "true")
    MilvusClientV2 milvusClient(MilvusMemoryProperties properties) {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri(properties.getUri())
                .dbName(properties.getDatabaseName())
                .connectTimeoutMs(properties.getConnectTimeout().toMillis())
                .rpcDeadlineMs(properties.getRequestTimeout().toMillis())
                .enablePrecheck(false);
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            builder.token(properties.getToken().trim());
        }
        return new MilvusClientV2(builder.build());
    }

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "app.memory.long-term", name = "enabled",
            havingValue = "true")
    LongTermMemoryStore longTermMemoryStore(
            MilvusLongTermMemoryStore milvusStore,
            InMemoryLongTermMemoryStore fallback
    ) {
        return new FailoverLongTermMemoryStore(milvusStore, fallback);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "app.memory.long-term", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    LongTermMemoryStore fallbackLongTermMemoryStore(InMemoryLongTermMemoryStore fallback) {
        return fallback;
    }
}
