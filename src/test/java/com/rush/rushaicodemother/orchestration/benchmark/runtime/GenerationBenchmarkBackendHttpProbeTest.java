package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedBackendRuntimeObservation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBenchmarkBackendHttpProbeTest {

    @Test
    void healthyJsonContractAndSecurityHeadersMustPass() throws IOException {
        byte[] body = "{\"code\":0,\"data\":{\"status\":\"ok\"},\"message\":\"ok\"}"
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = startServer(exchange -> respond(exchange, 200, body, true));
        try {
            GeneratedBackendRuntimeObservation observation = probe().awaitHealthy(
                    FakeProcess.running(),
                    server.getAddress().getPort()
            );

            assertTrue(observation.passedValidation(), observation.violations().toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedResponseMustReportProtocolAndSecurityViolations() throws IOException {
        byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);
        HttpServer server = startServer(exchange -> respond(exchange, 503, body, false));
        try {
            GeneratedBackendRuntimeObservation observation = probe().awaitHealthy(
                    FakeProcess.running(),
                    server.getAddress().getPort()
            );

            assertFalse(observation.passedValidation());
            assertTrue(observation.violations().contains("backend_health_status_invalid"));
            assertTrue(observation.violations().contains("backend_health_content_type_invalid"));
            assertTrue(observation.violations().contains("backend_security_header_missing"));
            assertTrue(observation.violations().contains("backend_health_json_invalid"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responseBodyMustBeReadUnderConfiguredLimit() throws IOException {
        byte[] body = new byte[2_048];
        HttpServer server = startServer(exchange -> respond(exchange, 200, body, true));
        try {
            GenerationBenchmarkBackendProperties properties = properties();
            properties.setMaxResponseBytes(1_024);
            GeneratedBackendRuntimeObservation observation = probe(properties).awaitHealthy(
                    FakeProcess.running(),
                    server.getAddress().getPort()
            );

            assertTrue(
                    observation.violations().contains("backend_health_body_too_large"),
                    observation.violations().toString()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exitedProcessMustFailWithoutWaitingForStartupTimeout() {
        GeneratedBackendRuntimeObservation observation = probe().awaitHealthy(
                FakeProcess.completed(),
                19_001
        );

        assertTrue(observation.violations().contains("backend_process_exited"));
    }

    private GenerationBenchmarkBackendHttpProbe probe() {
        return probe(properties());
    }

    private GenerationBenchmarkBackendHttpProbe probe(
            GenerationBenchmarkBackendProperties properties
    ) {
        return new GenerationBenchmarkBackendHttpProbe(
                properties,
                new ObjectMapper(),
                HttpClient.newBuilder()
                        .connectTimeout(properties.getRequestTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()
        );
    }

    private GenerationBenchmarkBackendProperties properties() {
        GenerationBenchmarkBackendProperties properties =
                new GenerationBenchmarkBackendProperties();
        properties.setStartupTimeout(Duration.ofSeconds(5));
        properties.setRequestTimeout(Duration.ofSeconds(1));
        properties.setPollInterval(Duration.ofMillis(10));
        return properties;
    }

    private HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/health", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private void respond(
            HttpExchange exchange,
            int status,
            byte[] body,
            boolean validHeaders
    ) throws IOException {
        if (validHeaders) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            exchange.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");
            exchange.getResponseHeaders().set(
                    "Referrer-Policy",
                    "strict-origin-when-cross-origin"
            );
        } else {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class FakeProcess extends Process {

        private final CountDownLatch exitLatch = new CountDownLatch(1);
        private volatile boolean alive;

        private FakeProcess(boolean alive) {
            this.alive = alive;
            if (!alive) {
                exitLatch.countDown();
            }
        }

        private static FakeProcess running() {
            return new FakeProcess(true);
        }

        private static FakeProcess completed() {
            return new FakeProcess(false);
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            exitLatch.await();
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exitLatch.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("进程仍在运行");
            }
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
            exitLatch.countDown();
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
