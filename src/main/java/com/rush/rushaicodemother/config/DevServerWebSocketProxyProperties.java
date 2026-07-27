package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 预览 WebSocket 代理的有界连接、消息和反压限制。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.dev-server.websocket-proxy")
public class DevServerWebSocketProxyProperties {

    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration sendTimeLimit = Duration.ofSeconds(10);
    private DataSize sendBufferSize = DataSize.ofMegabytes(2);
    private DataSize maxMessageSize = DataSize.ofMegabytes(1);

    @AssertTrue(message = "Dev Server WebSocket timeouts must be positive")
    public boolean isTimeoutConfigurationValid() {
        return positive(connectTimeout) && positive(sendTimeLimit);
    }

    @AssertTrue(message = "Dev Server WebSocket buffers must be positive and fit in a Java int")
    public boolean isBufferConfigurationValid() {
        return validSize(sendBufferSize) && validSize(maxMessageSize);
    }

    public int sendBufferSizeBytes() {
        return Math.toIntExact(sendBufferSize.toBytes());
    }

    public int maxMessageSizeBytes() {
        return Math.toIntExact(maxMessageSize.toBytes());
    }

    public int sendTimeLimitMillis() {
        return Math.toIntExact(sendTimeLimit.toMillis());
    }

    private boolean positive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            return false;
        }
        try {
            long millis = value.toMillis();
            return millis > 0 && millis <= Integer.MAX_VALUE;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private boolean validSize(DataSize value) {
        return value != null && value.toBytes() > 0 && value.toBytes() <= Integer.MAX_VALUE;
    }
}
