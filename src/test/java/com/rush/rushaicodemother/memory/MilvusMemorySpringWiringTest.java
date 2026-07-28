package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.MilvusMemoryConfiguration;
import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import com.rush.rushaicodemother.monitor.SemanticMemoryMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MilvusMemorySpringWiringTest {

    @Test
    void shouldWireProductionComponentsWithoutDefaultConstructors() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        MilvusMemoryCollectionManager.class,
                        MilvusLongTermMemoryStore.class,
                        MilvusMemoryStartupVerifier.class)
                .withPropertyValues("app.memory.long-term.enabled=true")
                .withBean(MilvusClientV2.class, () -> mock(MilvusClientV2.class))
                .withBean(MilvusMemoryProperties.class, this::enabledProperties)
                .withBean(MemoryEmbeddingService.class, () -> mock(MemoryEmbeddingService.class))
                .withBean(SemanticMemoryMetricsCollector.class, this::metrics)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MilvusMemoryCollectionManager.class);
                    assertThat(context).hasSingleBean(MilvusLongTermMemoryStore.class);
                    assertThat(context).hasSingleBean(MilvusMemoryStartupVerifier.class);
                });
    }

    @Test
    @Tag("external")
    void shouldSelectFailoverStoreWhenMilvusIsEnabled() {
        String uri = System.getProperty("milvusUri");
        Assumptions.assumeTrue(uri != null && !uri.isBlank(), "-DmilvusUri is required");
        new ApplicationContextRunner()
                .withUserConfiguration(MilvusMemoryConfiguration.class)
                .withPropertyValues("app.memory.long-term.enabled=true")
                .withBean(MilvusMemoryProperties.class, () -> enabledProperties(uri))
                .withBean(MilvusLongTermMemoryStore.class,
                        () -> mock(MilvusLongTermMemoryStore.class))
                .withBean(InMemoryLongTermMemoryStore.class, this::fallbackStore)
                .withBean(SemanticMemoryMetricsCollector.class, this::metrics)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MilvusClientV2.class);
                    assertThat(context.getBean(LongTermMemoryStore.class))
                            .isInstanceOf(FailoverLongTermMemoryStore.class);
                });
    }

    @Test
    void shouldSelectLocalStoreWithoutCreatingMilvusClientWhenDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(MilvusMemoryConfiguration.class)
                .withPropertyValues("app.memory.long-term.enabled=false")
                .withBean(MilvusMemoryProperties.class, MilvusMemoryProperties::new)
                .withBean(InMemoryLongTermMemoryStore.class, this::fallbackStore)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MilvusClientV2.class);
                    assertThat(context.getBean(LongTermMemoryStore.class))
                            .isInstanceOf(InMemoryLongTermMemoryStore.class);
                });
    }

    private MilvusMemoryProperties enabledProperties() {
        return enabledProperties("http://127.0.0.1:19530");
    }

    private MilvusMemoryProperties enabledProperties(String uri) {
        MilvusMemoryProperties properties = new MilvusMemoryProperties();
        properties.setEnabled(true);
        properties.setUri(uri);
        return properties;
    }

    private InMemoryLongTermMemoryStore fallbackStore() {
        return new InMemoryLongTermMemoryStore(new MilvusMemoryProperties());
    }

    private SemanticMemoryMetricsCollector metrics() {
        return new SemanticMemoryMetricsCollector(new SimpleMeterRegistry());
    }
}
