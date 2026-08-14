package com.rush.rushaicodemother.ai.model.transport;

import com.rush.rushaicodemother.infrastructure.security.AiModelOutboundDestinationPolicy;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;

/**
 * 为每个模型基础地址创建同源受限的 LangChain4j HTTP 客户端。
 *
 * <p>所有客户端共享一个由应用管理生命周期的连接池。连接池的 DNS 解析器会在
 * 实际建连时校验并固定公网 IP，重定向和底层隐式重试均被关闭。</p>
 */
@Component
public final class AiModelOutboundHttpClientFactory implements AutoCloseable {

    private static final int MAX_TOTAL_CONNECTIONS = 128;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 32;

    private final AiModelOutboundDestinationPolicy destinationPolicy;
    private final CancellableAiStreamingRequestExecutor streamingRequestExecutor;
    private final CloseableHttpClient apacheHttpClient;

    public AiModelOutboundHttpClientFactory(
            AiModelOutboundDestinationPolicy destinationPolicy,
            CancellableAiStreamingRequestExecutor streamingRequestExecutor) {
        this.destinationPolicy = Objects.requireNonNull(
                destinationPolicy, "AI 模型出站目的地址策略不能为空");
        this.streamingRequestExecutor = Objects.requireNonNull(
                streamingRequestExecutor, "AI 模型流式请求执行器不能为空");
        this.apacheHttpClient = createApacheClient(destinationPolicy);
    }

    /** 创建只允许访问指定模型基础地址的客户端 Builder。 */
    public HttpClientBuilder builderFor(String baseUrl) {
        AiModelOutboundDestinationPolicy.ApprovedDestination approved =
                destinationPolicy.approveBaseUrl(baseUrl);
        return new SecuredBuilder(
                apacheHttpClient,
                destinationPolicy,
                approved,
                streamingRequestExecutor
        );
    }

    private CloseableHttpClient createApacheClient(
            AiModelOutboundDestinationPolicy policy) {
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return policy.resolveAllowedAddresses(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                policy.resolveAllowedAddresses(host);
                return host;
            }
        };
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setMaxConnTotal(MAX_TOTAL_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableConnectionState()
                .evictExpiredConnections()
                .build();
    }

    @PreDestroy
    @Override
    public void close() {
        try {
            apacheHttpClient.close();
        } catch (IOException exception) {
            throw new IllegalStateException("关闭 AI 模型 HTTP 连接池失败", exception);
        }
    }

    private static final class SecuredBuilder implements HttpClientBuilder {

        private final CloseableHttpClient apacheHttpClient;
        private final AiModelOutboundDestinationPolicy destinationPolicy;
        private final AiModelOutboundDestinationPolicy.ApprovedDestination approved;
        private final CancellableAiStreamingRequestExecutor streamingRequestExecutor;
        private Duration connectTimeout;
        private Duration readTimeout;

        private SecuredBuilder(
                CloseableHttpClient apacheHttpClient,
                AiModelOutboundDestinationPolicy destinationPolicy,
                AiModelOutboundDestinationPolicy.ApprovedDestination approved,
                CancellableAiStreamingRequestExecutor streamingRequestExecutor) {
            this.apacheHttpClient = apacheHttpClient;
            this.destinationPolicy = destinationPolicy;
            this.approved = approved;
            this.streamingRequestExecutor = streamingRequestExecutor;
        }

        @Override
        public Duration connectTimeout() {
            return connectTimeout;
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            this.connectTimeout = requirePositive(timeout, "连接超时");
            return this;
        }

        @Override
        public Duration readTimeout() {
            return readTimeout;
        }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            this.readTimeout = requirePositive(timeout, "读取超时");
            return this;
        }

        @Override
        public HttpClient build() {
            return new SecuredAiHttpClient(
                    apacheHttpClient,
                    destinationPolicy,
                    approved,
                    streamingRequestExecutor,
                    connectTimeout,
                    readTimeout
            );
        }

        private Duration requirePositive(Duration timeout, String name) {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException(name + "必须大于 0");
            }
            return timeout;
        }
    }
}
