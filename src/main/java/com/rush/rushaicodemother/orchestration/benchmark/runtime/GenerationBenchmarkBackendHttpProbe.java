package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** 通过回环地址执行有界后端健康探测并校验响应契约。 */
@Component
public class GenerationBenchmarkBackendHttpProbe {

    private final GenerationBenchmarkBackendProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public GenerationBenchmarkBackendHttpProbe(
            GenerationBenchmarkBackendProperties properties,
            ObjectMapper objectMapper
    ) {
        this(
                properties,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(properties.getRequestTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .version(HttpClient.Version.HTTP_1_1)
                        .build()
        );
    }

    GenerationBenchmarkBackendHttpProbe(
            GenerationBenchmarkBackendProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public BackendRuntimeObservation awaitHealthy(Process process, int port) {
        if (process == null || port < 1 || port > 65_535) {
            return BackendRuntimeObservation.failed("backend_process_missing");
        }
        long startedAt = System.nanoTime();
        long timeoutNanos = properties.getStartupTimeout().toNanos();
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("后端运行时探测被中断");
            }
            if (!process.isAlive()) {
                return BackendRuntimeObservation.failed("backend_process_exited");
            }
            long elapsedNanos = System.nanoTime() - startedAt;
            long remainingNanos = timeoutNanos - elapsedNanos;
            if (remainingNanos <= 0) {
                return BackendRuntimeObservation.failed("backend_startup_timeout");
            }
            try {
                return inspect(port, Duration.ofNanos(remainingNanos));
            } catch (IOException exception) {
                sleepUntilNextAttempt(remainingNanos);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("后端运行时探测被中断", exception);
            }
        }
    }

    private BackendRuntimeObservation inspect(int port, Duration remaining) throws IOException, InterruptedException {
        Duration requestTimeout = properties.getRequestTimeout().compareTo(remaining) <= 0
                ? properties.getRequestTimeout()
                : remaining;
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/health")
                )
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );
        List<String> violations = new ArrayList<>();
        if (response.statusCode() != 200) {
            violations.add("backend_health_status_invalid");
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            violations.add("backend_health_content_type_invalid");
        }
        requireHeader(response, "X-Content-Type-Options", "nosniff", violations);
        requireHeader(response, "X-Frame-Options", "DENY", violations);
        requireHeader(response, "X-XSS-Protection", "1; mode=block", violations);
        requireHeader(
                response,
                "Referrer-Policy",
                "strict-origin-when-cross-origin",
                violations
        );
        byte[] body = readBoundedBody(response.body(), violations);
        if (body != null) {
            validateJsonContract(body, violations);
        }
        return new BackendRuntimeObservation(violations);
    }

    private byte[] readBoundedBody(InputStream body, List<String> violations) throws IOException {
        if (body == null) {
            violations.add("backend_health_body_missing");
            return null;
        }
        try (InputStream input = body) {
            byte[] bytes = input.readNBytes(properties.getMaxResponseBytes() + 1);
            if (bytes.length > properties.getMaxResponseBytes()) {
                violations.add("backend_health_body_too_large");
                return null;
            }
            return bytes;
        }
    }

    private void validateJsonContract(byte[] body, List<String> violations) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException exception) {
            violations.add("backend_health_json_invalid");
            return;
        }
        JsonNode code = root == null ? null : root.get("code");
        if (code == null || !code.isIntegralNumber() || code.intValue() != 0) {
            violations.add("backend_health_code_invalid");
        }
        JsonNode data = root == null ? null : root.get("data");
        JsonNode status = data == null || !data.isObject() ? null : data.get("status");
        if (status == null || !status.isTextual() || !"ok".equalsIgnoreCase(status.textValue())) {
            violations.add("backend_health_data_invalid");
        }
        JsonNode message = root == null ? null : root.get("message");
        if (message == null || !message.isTextual() || message.textValue().isBlank()) {
            violations.add("backend_health_message_invalid");
        }
    }

    private void requireHeader(
            HttpResponse<InputStream> response,
            String name,
            String expected,
            List<String> violations
    ) {
        String actual = response.headers().firstValue(name).orElse("");
        if (!expected.equalsIgnoreCase(actual.trim())) {
            violations.add("backend_security_header_missing");
        }
    }

    private void sleepUntilNextAttempt(long remainingNanos) {
        long sleepNanos = Math.min(properties.getPollInterval().toNanos(), remainingNanos);
        try {
            TimeUnit.NANOSECONDS.sleep(Math.max(1, sleepNanos));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("后端运行时探测被中断", exception);
        }
    }
}
