package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 预览 WebSocket 代理的固定连接、消息和反压限制。 */
@Data
@Component
@Validated
public class DevServerWebSocketProxyProperties {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration SEND_TIME_LIMIT = Duration.ofSeconds(10);
    public static final DataSize SEND_BUFFER_SIZE = DataSize.ofMegabytes(2);
    public static final DataSize MAX_MESSAGE_SIZE = DataSize.ofMegabytes(1);

    private Duration connectTimeout = CONNECT_TIMEOUT;
    private Duration sendTimeLimit = SEND_TIME_LIMIT;
    private DataSize sendBufferSize = SEND_BUFFER_SIZE;
    private DataSize maxMessageSize = MAX_MESSAGE_SIZE;

    @AssertTrue(message = "Dev Server WebSocket 超时必须全部大于 0")
    public boolean isTimeoutConfigurationValid() {
        return positive(connectTimeout) && positive(sendTimeLimit);
    }

    @AssertTrue(message = "Dev Server WebSocket 缓冲区必须大于 0 且不超过 Java int 上限")
    public boolean isBufferConfigurationValid() {
        return validSize(sendBufferSize) && validSize(maxMessageSize);
    }

    /**
 * 返回发送缓冲区大小对应的字节数。
 *
 * @return 计算或处理后的数值结果
 */
    public int sendBufferSizeBytes() {
        return Math.toIntExact(sendBufferSize.toBytes());
    }

    /**
 * 返回最大消息大小对应的字节数。
 *
 * @return 计算或处理后的数值结果
 */
    public int maxMessageSizeBytes() {
        return Math.toIntExact(maxMessageSize.toBytes());
    }

    /**
 * 返回发送时间限制对应的毫秒数。
 *
 * @return 计算或处理后的数值结果
 */
    public int sendTimeLimitMillis() {
        return Math.toIntExact(sendTimeLimit.toMillis());
    }

    /** 判断给定时长或数值是否为正数。 */
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
