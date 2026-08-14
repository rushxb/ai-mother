package com.rush.rushaicodemother.ai.model.transport;

import com.rush.rushaicodemother.infrastructure.security.AiModelOutboundDestinationPolicy;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.http.client.FormDataFile;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.core.task.AsyncTaskExecutor;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static dev.langchain4j.http.client.sse.ServerSentEventListenerUtils.ignoringExceptions;

/** Apache HttpClient5 上的受限 AI 模型传输适配器。 */
final class SecuredAiHttpClient implements HttpClient {

    private static final int MAX_ERROR_BODY_CHARACTERS = 8192;
    private static final Set<String> TRANSPORT_OWNED_HEADERS = Set.of(
            "connection", "content-length", "content-type", "host", "proxy-authorization",
            "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade"
    );

    private final CloseableHttpClient apacheHttpClient;
    private final AiModelOutboundDestinationPolicy destinationPolicy;
    private final AiModelOutboundDestinationPolicy.ApprovedDestination approved;
    private final AsyncTaskExecutor streamingRequestExecutor;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    SecuredAiHttpClient(CloseableHttpClient apacheHttpClient,
                        AiModelOutboundDestinationPolicy destinationPolicy,
                        AiModelOutboundDestinationPolicy.ApprovedDestination approved,
                        AsyncTaskExecutor streamingRequestExecutor,
                        Duration connectTimeout,
                        Duration readTimeout) {
        this.apacheHttpClient = apacheHttpClient;
        this.destinationPolicy = destinationPolicy;
        this.approved = approved;
        this.streamingRequestExecutor = streamingRequestExecutor;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException {
        HttpUriRequestBase outboundRequest = toApacheRequest(request);
        try (CloseableHttpResponse response = apacheHttpClient.execute(outboundRequest)) {
            if (response.getCode() < 200 || response.getCode() >= 300) {
                throw new HttpException(response.getCode(), readErrorBody(response.getEntity()));
            }
            String body = response.getEntity() == null
                    ? null
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            return SuccessfulHttpResponse.builder()
                    .statusCode(response.getCode())
                    .headers(headersOf(response.getHeaders()))
                    .body(body)
                    .build();
        } catch (Exception exception) {
            throw mapFailure(exception);
        }
    }

    @Override
    public void execute(HttpRequest request,
                        ServerSentEventParser parser,
                        ServerSentEventListener listener) {
        streamingRequestExecutor.execute(() -> executeStreaming(request, parser, listener));
    }

    private void executeStreaming(HttpRequest request,
                                  ServerSentEventParser parser,
                                  ServerSentEventListener listener) {
        try {
            HttpUriRequestBase outboundRequest = toApacheRequest(request);
            try (CloseableHttpResponse response = apacheHttpClient.execute(outboundRequest)) {
                if (response.getCode() < 200 || response.getCode() >= 300) {
                    String errorBody = readErrorBody(response.getEntity());
                    ignoringExceptions(() -> listener.onError(
                            new HttpException(response.getCode(), errorBody)));
                    return;
                }
                SuccessfulHttpResponse opened = SuccessfulHttpResponse.builder()
                        .statusCode(response.getCode())
                        .headers(headersOf(response.getHeaders()))
                        .build();
                ignoringExceptions(() -> listener.onOpen(opened));
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    try (InputStream inputStream = entity.getContent()) {
                        parser.parse(inputStream, listener);
                    }
                }
                ignoringExceptions(listener::onClose);
            }
        } catch (Exception exception) {
            RuntimeException failure = mapFailure(exception);
            ignoringExceptions(() -> listener.onError(failure));
        }
    }

    private HttpUriRequestBase toApacheRequest(HttpRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("AI 模型 HTTP 请求不能为空");
        }
        URI requestUri = URI.create(request.url());
        destinationPolicy.requireAllowedRequest(approved, requestUri);

        HttpUriRequestBase outbound = new HttpUriRequestBase(request.method().name(), requestUri);
        outbound.setConfig(requestConfig());
        request.headers().forEach((name, values) -> {
            if (isTransportOwnedHeader(name)) {
                return;
            }
            values.forEach(value -> outbound.addHeader(name, value));
        });
        outbound.setEntity(requestEntity(request));
        return outbound;
    }

    private RequestConfig requestConfig() {
        RequestConfig.Builder builder = RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setAuthenticationEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .setHardCancellationEnabled(true);
        if (connectTimeout != null) {
            Timeout timeout = Timeout.ofMilliseconds(connectTimeout.toMillis());
            builder.setConnectTimeout(timeout).setConnectionRequestTimeout(timeout);
        }
        if (readTimeout != null) {
            builder.setResponseTimeout(Timeout.ofMilliseconds(readTimeout.toMillis()));
        }
        return builder.build();
    }

    private HttpEntity requestEntity(HttpRequest request) {
        if (!request.formDataFields().isEmpty() || !request.formDataFiles().isEmpty()) {
            MultipartEntityBuilder multipart = MultipartEntityBuilder.create()
                    .setCharset(StandardCharsets.UTF_8);
            request.formDataFields().forEach((name, value) -> multipart.addTextBody(
                    name, value, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8)));
            request.formDataFiles().forEach((name, file) -> multipart.addBinaryBody(
                    name,
                    file.content(),
                    contentTypeOf(file),
                    file.fileName()
            ));
            return multipart.build();
        }
        return request.body() == null
                ? null
                : new StringEntity(request.body(), ContentType.APPLICATION_JSON);
    }

    private ContentType contentTypeOf(FormDataFile file) {
        try {
            return file.contentType() == null
                    ? ContentType.APPLICATION_OCTET_STREAM
                    : ContentType.parse(file.contentType());
        } catch (RuntimeException exception) {
            return ContentType.APPLICATION_OCTET_STREAM;
        }
    }

    private boolean isTransportOwnedHeader(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return TRANSPORT_OWNED_HEADERS.contains(normalized);
    }

    private String readErrorBody(HttpEntity entity) throws IOException {
        if (entity == null) {
            return null;
        }
        try {
            return EntityUtils.toString(
                    entity, StandardCharsets.UTF_8, MAX_ERROR_BODY_CHARACTERS);
        } catch (org.apache.hc.core5.http.ParseException exception) {
            throw new IOException("AI provider error response is malformed", exception);
        }
    }

    private Map<String, List<String>> headersOf(Header[] headers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Header header : headers) {
            result.computeIfAbsent(header.getName(), ignored -> new ArrayList<>())
                    .add(header.getValue());
        }
        result.replaceAll((ignored, values) -> List.copyOf(values));
        return Map.copyOf(result);
    }

    private RuntimeException mapFailure(Exception exception) {
        if (exception instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (exception instanceof SocketTimeoutException
                || exception instanceof java.io.InterruptedIOException) {
            return new TimeoutException(exception);
        }
        return new IllegalStateException("AI provider HTTP request failed", exception);
    }
}
