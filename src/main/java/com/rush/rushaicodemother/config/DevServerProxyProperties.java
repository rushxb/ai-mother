package com.rush.rushaicodemother.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Dev Server 反向代理的资源和超时限制。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.dev-server.proxy")
public class DevServerProxyProperties {

    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(30);
    private DataSize maxRequestBody = DataSize.ofMegabytes(2);
    private DataSize maxResponseBody = DataSize.ofMegabytes(25);
}
