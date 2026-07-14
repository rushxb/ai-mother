package com.rush.rushaicodemother.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.qcloud.cos.COSClient;
import com.rush.rushaicodemother.service.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CosClientConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(CosClientConfig.class);

    @Test
    void shouldNotCreateStorageRuntimeWhenCosIsDisabled() {
        contextRunner
                .withPropertyValues("cos.client.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(COSClient.class);
                    assertThat(context).doesNotHaveBean(ObjectStorageService.class);
                });
    }

    @Test
    void shouldCreateClientAndStorageAdapterWhenCosIsEnabled() {
        Logger cosHttpClientLogger =
                (Logger) LoggerFactory.getLogger("com.qcloud.cos.http.DefaultCosHttpClient");
        Level originalLevel = cosHttpClientLogger.getLevel();
        cosHttpClientLogger.setLevel(Level.WARN);
        try {
            contextRunner
                    .withPropertyValues(
                            "cos.client.enabled=true",
                            "cos.client.host=http://localhost:9000",
                            "cos.client.secret-id=test-secret-id",
                            "cos.client.secret-key=test-secret-key",
                            "cos.client.region=ap-beijing",
                            "cos.client.bucket=bucket-123"
                    )
                    .run(context -> {
                        assertThat(context).hasSingleBean(COSClient.class);
                        assertThat(context).hasSingleBean(ObjectStorageService.class);
                    });
        } finally {
            cosHttpClientLogger.setLevel(originalLevel);
        }
    }
}
