package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.config.ScreenshotProperties;
import com.rush.rushaicodemother.infrastructure.browser.selenium.SeleniumBrowserRuntimeProbe;
import com.rush.rushaicodemother.infrastructure.screenshot.selenium.SeleniumChromeDriverFactory;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeObservation;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用部署方显式提供的 Chrome 与 ChromeDriver 验证真实浏览器证据链。 */
@Tag("integration")
@Tag("generation-browser-smoke")
class SeleniumBrowserRuntimeSmokeIntegrationTest {

    @Test
    void realBrowserMustCollectLoopbackDomNetworkConsoleAndScreenshotEvidence() throws Exception {
        HttpServer server = startServer();
        try {
            ScreenshotProperties properties = browserProperties();
            SeleniumBrowserRuntimeProbe probe = new SeleniumBrowserRuntimeProbe(
                    new SeleniumChromeDriverFactory(properties), properties);
            URI target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");

            BrowserRuntimeObservation observation = probe.inspect(target, Duration.ZERO);

            assertEquals(target, observation.requestedUri());
            assertEquals("benchmark-browser-smoke", observation.title());
            assertEquals("complete", observation.readyState());
            assertEquals("真实浏览器证据", observation.firstHeading());
            assertTrue(observation.appNodeExists());
            assertTrue(observation.visibleElementCount() > 0);
            assertFalse(observation.hasFatalConsoleError());
            assertFalse(observation.hasNetworkFailure());
            assertTrue(observation.networkEvidence().captured());
            assertTrue(observation.screenshot().captured());
            assertTrue(observation.screenshot().width() > 0);
            assertTrue(observation.screenshot().height() > 0);
        } finally {
            server.stop(0);
        }
    }

    private ScreenshotProperties browserProperties() {
        ScreenshotProperties properties = new ScreenshotProperties();
        properties.setEnabled(true);
        properties.setChromeDriverPath(requiredProperty("integration.chrome.driver"));
        properties.setChromeBinaryPath(requiredProperty("integration.chrome.binary"));
        properties.setPageLoadTimeout(Duration.ofSeconds(20));
        properties.setReadyStateTimeout(Duration.ofSeconds(10));
        return properties;
    }

    private HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        byte[] response = """
                <!doctype html>
                <html lang="zh-CN">
                  <head><meta charset="UTF-8"><title>benchmark-browser-smoke</title></head>
                  <body>
                    <main id="app"><h1>真实浏览器证据</h1><p>loopback only</p></main>
                    <script>console.info('browser smoke ready')</script>
                  </body>
                </html>
                """.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        return server;
    }

    private String requiredProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("真实浏览器集成测试缺少 JVM 参数: " + key);
        }
        return value.trim();
    }
}
