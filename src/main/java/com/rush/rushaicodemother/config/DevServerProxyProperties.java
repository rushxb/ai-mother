package com.rush.rushaicodemother.config;

import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Dev Server 反向代理的固定资源和超时限制。
 */
@Data
@Component
public class DevServerProxyProperties {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    public static final DataSize MAX_REQUEST_BODY = DataSize.ofMegabytes(2);
    public static final DataSize MAX_RESPONSE_BODY = DataSize.ofMegabytes(25);

    private Duration connectTimeout = CONNECT_TIMEOUT;
    private Duration requestTimeout = REQUEST_TIMEOUT;
    private DataSize maxRequestBody = MAX_REQUEST_BODY;
    private DataSize maxResponseBody = MAX_RESPONSE_BODY;
}
