package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本机 Dev Server 受限反向代理。
 * 目标主机固定为回环地址，调用方只能提供已分配端口和应用内路径。
 */
@Slf4j
@Service
public class DevServerProxyService {

    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> ALLOWED_METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
    );
    private static final int COPY_BUFFER_SIZE = 8192;

    private final DevServerProxyProperties properties;
    private final ProxyHeaderPolicy headerPolicy;
    private final HttpClient httpClient;

    @Autowired
    public DevServerProxyService(DevServerProxyProperties properties, ProxyHeaderPolicy headerPolicy) {
        this(properties, headerPolicy, HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    DevServerProxyService(DevServerProxyProperties properties,
                          ProxyHeaderPolicy headerPolicy,
                          HttpClient httpClient) {
        validateProperties(properties);
        this.properties = properties;
        this.headerPolicy = headerPolicy;
        this.httpClient = httpClient;
    }

    public void proxy(int port,
                      String path,
                      String queryString,
                      HttpServletRequest request,
                      HttpServletResponse response) {
        if (port < 1 || port > 65535 || path == null || !path.startsWith("/")) {
            writeError(response, HttpStatus.BAD_REQUEST.value(), "非法代理目标");
            return;
        }

        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            writeError(response, HttpStatus.METHOD_NOT_ALLOWED.value(), "不支持的请求方法");
            return;
        }

        try {
            byte[] requestBody = readRequestBody(method, request);
            URI targetUri = buildTargetUri(port, path, queryString);
            HttpRequest upstreamRequest = buildUpstreamRequest(targetUri, method, requestBody, request);
            HttpResponse<InputStream> upstreamResponse = httpClient.send(
                    upstreamRequest,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            try (InputStream responseBody = upstreamResponse.body()) {
                byte[] body = readLimited(
                        responseBody,
                        properties.getMaxResponseBody().toBytes(),
                        HttpStatus.BAD_GATEWAY.value(),
                        "Dev Server 响应体超过限制"
                );
                writeUpstreamResponse(upstreamResponse, body, response);
            }
        } catch (PayloadTooLargeException exception) {
            writeError(response, exception.statusCode(), exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Dev Server 代理请求被中断，port={}，path={}", port, path);
            writeError(response, HttpStatus.BAD_GATEWAY.value(), "Dev Server 代理请求失败");
        } catch (IllegalArgumentException | IOException exception) {
            log.warn("Dev Server 代理请求失败，port={}，path={}，error={}",
                    port, path, exception.getClass().getSimpleName());
            writeError(response, HttpStatus.BAD_GATEWAY.value(), "Dev Server 代理请求失败");
        }
    }

    private HttpRequest buildUpstreamRequest(URI targetUri,
                                             String method,
                                             byte[] requestBody,
                                             HttpServletRequest request) {
        HttpRequest.BodyPublisher publisher = requestBody.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(requestBody);
        HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri)
                .timeout(properties.getRequestTimeout())
                .method(method, publisher);

        Collections.list(request.getHeaderNames()).forEach(headerName -> {
            if (!headerPolicy.shouldForwardRequestHeader(headerName)) {
                return;
            }
            Collections.list(request.getHeaders(headerName)).forEach(headerValue -> {
                try {
                    builder.header(headerName, headerValue);
                } catch (IllegalArgumentException ignored) {
                    log.debug("忽略无法转发的请求头: {}", headerName);
                }
            });
        });
        return builder.build();
    }

    private byte[] readRequestBody(String method, HttpServletRequest request) throws IOException {
        if (!BODY_METHODS.contains(method)) {
            return new byte[0];
        }
        long maxBytes = properties.getMaxRequestBody().toBytes();
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBytes) {
            throw new PayloadTooLargeException(
                    HttpStatus.PAYLOAD_TOO_LARGE.value(),
                    "请求体超过代理限制"
            );
        }
        return readLimited(
                request.getInputStream(),
                maxBytes,
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "请求体超过代理限制"
        );
    }

    private byte[] readLimited(InputStream inputStream,
                               long maxBytes,
                               int statusCode,
                               String errorMessage) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long totalBytes = 0;
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            totalBytes += bytesRead;
            if (totalBytes > maxBytes) {
                throw new PayloadTooLargeException(statusCode, errorMessage);
            }
            outputStream.write(buffer, 0, bytesRead);
        }
        return outputStream.toByteArray();
    }

    private void writeUpstreamResponse(HttpResponse<InputStream> upstreamResponse,
                                       byte[] body,
                                       HttpServletResponse response) throws IOException {
        response.setStatus(upstreamResponse.statusCode());
        upstreamResponse.headers().map().forEach((headerName, values) -> {
            if (!headerPolicy.shouldForwardResponseHeader(headerName)) {
                return;
            }
            for (String value : values) {
                response.addHeader(headerName, value);
            }
        });
        response.setContentLengthLong(body.length);
        if (body.length > 0) {
            response.getOutputStream().write(body);
        }
    }

    private URI buildTargetUri(int port, String path, String queryString) {
        if (containsControlCharacter(path) || containsControlCharacter(queryString)) {
            throw new IllegalArgumentException("代理路径包含非法字符");
        }
        String target = "http://127.0.0.1:" + port + path;
        if (queryString != null && !queryString.isBlank()) {
            target += "?" + queryString;
        }
        return URI.create(target);
    }

    private boolean containsControlCharacter(String value) {
        if (value == null) {
            return false;
        }
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7F);
    }

    private void writeError(HttpServletResponse response, int statusCode, String message) {
        if (response.isCommitted()) {
            return;
        }
        try {
            response.reset();
            response.setStatus(statusCode);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(message);
        } catch (IOException exception) {
            log.debug("写入 Dev Server 代理错误响应失败", exception);
        }
    }

    private void validateProperties(DevServerProxyProperties proxyProperties) {
        Duration connectTimeout = proxyProperties.getConnectTimeout();
        Duration requestTimeout = proxyProperties.getRequestTimeout();
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("Dev Server 代理连接超时必须大于 0");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Dev Server 代理请求超时必须大于 0");
        }
        if (proxyProperties.getMaxRequestBody() == null || proxyProperties.getMaxRequestBody().toBytes() <= 0) {
            throw new IllegalArgumentException("Dev Server 代理请求体限制必须大于 0");
        }
        if (proxyProperties.getMaxResponseBody() == null || proxyProperties.getMaxResponseBody().toBytes() <= 0) {
            throw new IllegalArgumentException("Dev Server 代理响应体限制必须大于 0");
        }
    }

    private static final class PayloadTooLargeException extends IOException {

        private final int statusCode;

        private PayloadTooLargeException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }
}
