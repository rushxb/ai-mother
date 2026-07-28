package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
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
import java.util.Map;
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
    private final DevServerInternalRequestSigner internalRequestSigner;
    private final DevServerPreviewTargetResolver targetResolver;
    private final HttpClient httpClient;

    @Autowired
    public DevServerProxyService(DevServerProxyProperties properties,
                                 ProxyHeaderPolicy headerPolicy,
                                 DevServerInternalRequestSigner internalRequestSigner,
                                 DevServerPreviewTargetResolver targetResolver) {
        this(properties, headerPolicy, internalRequestSigner, targetResolver, HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    DevServerProxyService(DevServerProxyProperties properties,
                          ProxyHeaderPolicy headerPolicy,
                          DevServerInternalRequestSigner internalRequestSigner,
                          DevServerPreviewTargetResolver targetResolver,
                          HttpClient httpClient) {
        validateProperties(properties);
        this.properties = properties;
        this.headerPolicy = headerPolicy;
        this.internalRequestSigner = internalRequestSigner;
        this.targetResolver = targetResolver;
        this.httpClient = httpClient;
    }

    /**
 * 处理代理。
 *
 * @param route 代理路由
 * @param path 目标路径
 * @param queryString 原始查询字符串
 * @param request 请求参数
 * @param response 响应对象
 */
    public void proxy(DevServerPreviewRoute route,
                      String path,
                      String queryString,
                      HttpServletRequest request,
                      HttpServletResponse response) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (route == null || path == null || !path.startsWith("/")) {
            writeError(response, HttpStatus.BAD_REQUEST.value(), "非法代理目标");
            return;
        }

        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            writeError(response, HttpStatus.METHOD_NOT_ALLOWED.value(), "不支持的请求方法");
            return;
        }

        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            byte[] requestBody = readRequestBody(method, request);
            URI targetUri = targetResolver.httpTarget(route, path, queryString);
            Map<String, String> internalHeaders = route.local()
                    ? Map.of()
                    : internalRequestSigner.sign(method, targetUri, requestBody);
            HttpRequest upstreamRequest = buildUpstreamRequest(
                    targetUri, method, requestBody, request, internalHeaders);
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
            log.warn("Dev Server 代理请求被中断，node={}，port={}，path={}",
                    route.nodeId(), route.port(), path);
            writeError(response, HttpStatus.BAD_GATEWAY.value(), "Dev Server 代理请求失败");
        } catch (IllegalArgumentException | IOException exception) {
            log.warn("Dev Server 代理请求失败，node={}，port={}，path={}，error={}",
                    route.nodeId(), route.port(), path, exception.getClass().getSimpleName());
            writeError(response, HttpStatus.BAD_GATEWAY.value(), "Dev Server 代理请求失败");
        }
    }

    /**
 * 处理代理{@code Local}。
 *
 * @param appId 应用编号
 * @param port 端口
 * @param path 目标路径
 * @param queryString 原始查询字符串
 * @param request 请求参数
 * @param response 响应对象
 * @param verifiedRequest {@code verifiedRequest} 对应的调用参数
 */
    public void proxyLocal(Long appId,
                           int port,
                           String path,
                           String queryString,
                           HttpServletRequest request,
                           HttpServletResponse response,
                           VerifiedDevServerInternalRequest verifiedRequest) {
        if (port < 1 || port > 65535) {
            writeError(response, HttpStatus.BAD_REQUEST.value(), "非法代理目标");
            return;
        }
        proxyInternal(
                appId,
                port,
                path,
                queryString,
                request,
                response,
                verifiedRequest
        );
    }

    /** 处理代理内部。 */
    private void proxyInternal(Long appId,
                               int port,
                               String path,
                               String queryString,
                               HttpServletRequest request,
                               HttpServletResponse response,
                               VerifiedDevServerInternalRequest verifiedRequest) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (appId == null || appId <= 0 || port < 1 || port > 65535
                || path == null || !path.startsWith("/")) {
            writeError(response, HttpStatus.BAD_REQUEST.value(), "非法代理目标");
            return;
        }
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            writeError(response, HttpStatus.METHOD_NOT_ALLOWED.value(), "不支持的请求方法");
            return;
        }
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            byte[] requestBody = readRequestBody(method, request);
            internalRequestSigner.verifyBody(verifiedRequest, requestBody);
            URI targetUri = targetResolver.localHttpTarget(appId, port, path, queryString);
            HttpRequest upstreamRequest = buildUpstreamRequest(
                    targetUri, method, requestBody, request, Map.of());
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
            writeError(response, HttpStatus.BAD_GATEWAY.value(), "Dev Server 代理请求失败");
        } catch (IllegalArgumentException | IOException exception) {
            writeError(response, HttpStatus.BAD_GATEWAY.value(), "Dev Server 代理请求失败");
        }
    }

    /** 构建并返回{@code Upstream}请求。 */
    private HttpRequest buildUpstreamRequest(URI targetUri,
                                             String method,
                                             byte[] requestBody,
                                             HttpServletRequest request,
                                             Map<String, String> additionalHeaders) {
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
        additionalHeaders.forEach(builder::header);
        return builder.build();
    }

    /** 读取请求正文。 */
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

    /** 读取{@code Limited}。 */
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

    /** 写入{@code Upstream}响应。 */
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

    /** 写入错误。 */
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
            log.debug("写入 Dev Server 代理错误响应失败", LogExceptionSanitizer.sanitize(exception));
        }
    }

    /** 校验{@code ate}属性是否有效。 */
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
