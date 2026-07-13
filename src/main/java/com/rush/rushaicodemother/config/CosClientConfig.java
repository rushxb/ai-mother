package com.rush.rushaicodemother.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.region.Region;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * 腾讯云 COS 客户端装配。
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(CosClientProperties.class)
public class CosClientConfig {

    private final CosClientProperties properties;

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "cos.client", name = "enabled", havingValue = "true")
    public COSClient cosClient() {
        COSCredentials credentials = new BasicCOSCredentials(
                properties.getSecretId(),
                properties.getSecretKey()
        );
        ClientConfig clientConfig = new ClientConfig(new Region(properties.getRegion()));
        return new COSClient(credentials, clientConfig);
    }
}
