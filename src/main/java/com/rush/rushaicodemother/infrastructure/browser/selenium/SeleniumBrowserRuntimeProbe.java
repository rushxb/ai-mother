package com.rush.rushaicodemother.infrastructure.browser.selenium;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.config.ScreenshotProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.infrastructure.screenshot.selenium.SeleniumChromeDriverFactory;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeObservation;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeProbe;
import com.rush.rushaicodemother.service.browser.LoopbackBrowserTargetPolicy;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selenium 实现具有仅环回目标验证和有限证据捕获。 */
@Slf4j
@Component
public class SeleniumBrowserRuntimeProbe implements BrowserRuntimeProbe {

    private static final int MAX_CONSOLE_MESSAGES = 50;
    private static final int MAX_NETWORK_FAILURES = 50;
    private static final Duration MAX_SETTLE_DELAY = Duration.ofSeconds(30);

    private final SeleniumChromeDriverFactory driverFactory;
    private final ScreenshotProperties properties;

    public SeleniumBrowserRuntimeProbe(
            SeleniumChromeDriverFactory driverFactory,
            ScreenshotProperties properties
    ) {
        this.driverFactory = driverFactory;
        this.properties = properties;
    }

    /**
 * 返回{@code inspect}。
 *
 * @param targetUri {@code targetUri} 对应的调用参数
 * @param settleDelay {@code settleDelay} 对应的调用参数
 * @return {@code Selenium}浏览器运行时{@code Probe}
 */
    @Override
    public BrowserRuntimeObservation inspect(URI targetUri, Duration settleDelay) {
        URI allowedTarget = LoopbackBrowserTargetPolicy.requireAllowed(targetUri);
        WebDriver driver = null;
        try {
            driver = driverFactory.createIsolatedDiagnosticDriver();
            driver.get(allowedTarget.toASCIIString());
            waitForReadyState(driver);
            awaitSettleDelay(settleDelay);
            URI finalUri = URI.create(driver.getCurrentUrl()).normalize();
            LoopbackBrowserTargetPolicy.requireAllowed(finalUri);
            JSONObject page = collectPageEvidence(driver);
            return new BrowserRuntimeObservation(
                    allowedTarget,
                    finalUri,
                    page.getStr("title", ""),
                    page.getStr("readyState", ""),
                    page.getInt("bodyTextLength", 0),
                    page.getInt("bodyChildCount", 0),
                    page.getBool("appNodeExists", false),
                    page.getInt("appNodeChildCount", 0),
                    page.getInt("visibleElementCount", 0),
                    page.getInt("documentWidth", 0),
                    page.getInt("documentHeight", 0),
                    page.getBool("viteErrorOverlayPresent", false),
                    page.getStr("firstText", ""),
                    page.getStr("firstHeading", ""),
                    stringList(page, "scripts"),
                    stringList(page, "stylesheets"),
                    collectConsoleMessages(driver),
                    collectNetworkEvidence(driver),
                    collectScreenshotStats(driver)
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("browser runtime probe was interrupted", exception);
        } finally {
            closeDriver(driver);
        }
    }

    private void waitForReadyState(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, properties.getReadyStateTimeout());
        wait.until(current -> "complete".equals(
                ((JavascriptExecutor) current).executeScript("return document.readyState")
        ));
    }

    /** 等待{@code Settle}延迟完成。 */
    private void awaitSettleDelay(Duration requestedDelay) throws InterruptedException {
        Duration delay = requestedDelay == null ? Duration.ZERO : requestedDelay;
        if (delay.isNegative()) {
            delay = Duration.ZERO;
        }
        if (delay.compareTo(MAX_SETTLE_DELAY) > 0) {
            delay = MAX_SETTLE_DELAY;
        }
        if (!delay.isZero()) {
            Thread.sleep(delay.toMillis());
        }
    }

    /** 采集并汇总页面证据。 */
    private JSONObject collectPageEvidence(WebDriver driver) {
        String script = """
                const body = document.body;
                const app = document.querySelector('#app, #root, [data-v-app]');
                const visibleElementCount = body ? Array.from(body.querySelectorAll('*')).filter(element => {
                  const style = window.getComputedStyle(element);
                  const rect = element.getBoundingClientRect();
                  return style.display !== 'none'
                    && style.visibility !== 'hidden'
                    && Number(style.opacity || '1') > 0
                    && rect.width > 1
                    && rect.height > 1;
                }).length : 0;
                return JSON.stringify({
                  title: document.title || '',
                  readyState: document.readyState || '',
                  bodyTextLength: body ? body.innerText.trim().length : 0,
                  bodyChildCount: body ? body.children.length : 0,
                  appNodeExists: !!app,
                  appNodeChildCount: app ? app.children.length : 0,
                  visibleElementCount,
                  documentWidth: Math.max(
                    document.documentElement ? document.documentElement.scrollWidth : 0,
                    body ? body.scrollWidth : 0
                  ),
                  documentHeight: Math.max(
                    document.documentElement ? document.documentElement.scrollHeight : 0,
                    body ? body.scrollHeight : 0
                  ),
                  viteErrorOverlayPresent: !!document.querySelector('vite-error-overlay'),
                  firstText: body ? body.innerText.replace(/\\s+/g, ' ').trim().slice(0, 512) : '',
                  firstHeading: (() => {
                    const heading = document.querySelector('h1, h2, [role="heading"]');
                    return heading ? heading.innerText.replace(/\\s+/g, ' ').trim().slice(0, 256) : '';
                  })(),
                  scripts: Array.from(document.scripts)
                    .map(item => item.src)
                    .filter(Boolean)
                    .slice(0, 20),
                  stylesheets: Array.from(document.querySelectorAll('link[rel="stylesheet"]'))
                    .map(item => item.href)
                    .filter(Boolean)
                    .slice(0, 20)
                });
                """;
        Object value = ((JavascriptExecutor) driver).executeScript(script);
        return JSONUtil.parseObj(StrUtil.blankToDefault(String.valueOf(value), "{}"));
    }

    /** 采集并汇总{@code Console}消息。 */
    private List<BrowserRuntimeObservation.ConsoleMessage> collectConsoleMessages(WebDriver driver) {
        List<BrowserRuntimeObservation.ConsoleMessage> messages = new ArrayList<>();
        for (LogEntry entry : driver.manage().logs().get(LogType.BROWSER)) {
            messages.add(new BrowserRuntimeObservation.ConsoleMessage(
                    entry.getLevel().getName(),
                    entry.getMessage()
            ));
            if (messages.size() >= MAX_CONSOLE_MESSAGES) {
                break;
            }
        }
        return List.copyOf(messages);
    }

    /** 从 Chrome DevTools performance log 汇总 HTTP 失败和未获得响应的加载失败。 */
    private BrowserRuntimeObservation.NetworkEvidence collectNetworkEvidence(WebDriver driver) {
        try {
            Map<String, String> requestUrls = new HashMap<>();
            List<BrowserRuntimeObservation.NetworkFailure> failures = new ArrayList<>();
            for (LogEntry entry : driver.manage().logs().get(LogType.PERFORMANCE)) {
                collectNetworkEntry(entry, requestUrls, failures);
                if (failures.size() >= MAX_NETWORK_FAILURES) {
                    break;
                }
            }
            return BrowserRuntimeObservation.NetworkEvidence.captured(failures);
        } catch (RuntimeException exception) {
            log.warn("浏览器 network performance log 采集不可用: error={}",
                    LogExceptionSanitizer.sanitizeMessage(exception));
            return BrowserRuntimeObservation.NetworkEvidence.unavailable();
        }
    }

    private void collectNetworkEntry(
            LogEntry entry,
            Map<String, String> requestUrls,
            List<BrowserRuntimeObservation.NetworkFailure> failures
    ) {
        if (entry == null || entry.getMessage() == null || entry.getMessage().isBlank()) {
            return;
        }
        try {
            JSONObject envelope = JSONUtil.parseObj(entry.getMessage());
            JSONObject message = envelope.getJSONObject("message");
            if (message == null) {
                return;
            }
            String method = message.getStr("method", "");
            JSONObject params = message.getJSONObject("params");
            if (params == null) {
                return;
            }
            String requestId = params.getStr("requestId", "");
            if ("Network.requestWillBeSent".equals(method)) {
                JSONObject request = params.getJSONObject("request");
                if (request != null && !requestId.isBlank()) {
                    requestUrls.put(requestId, request.getStr("url", ""));
                }
                return;
            }
            if ("Network.responseReceived".equals(method)) {
                JSONObject response = params.getJSONObject("response");
                if (response == null) {
                    return;
                }
                int status = response.getInt("status", 0);
                if (status >= 400) {
                    failures.add(new BrowserRuntimeObservation.NetworkFailure(
                            response.getStr("url", requestUrls.getOrDefault(requestId, "")),
                            status,
                            response.getStr("statusText", "HTTP " + status)
                    ));
                }
                return;
            }
            if ("Network.loadingFailed".equals(method)
                    && !params.getBool("canceled", false)) {
                failures.add(new BrowserRuntimeObservation.NetworkFailure(
                        requestUrls.getOrDefault(requestId, ""),
                        0,
                        params.getStr("errorText", "network loading failed")
                ));
            }
        } catch (RuntimeException exception) {
            log.debug("忽略无法解析的浏览器 performance log 条目: error={}",
                    LogExceptionSanitizer.sanitizeMessage(exception));
        }
    }

    /** 汇总截图结果的尺寸和内容统计。 */
    private BrowserRuntimeObservation.ScreenshotStats collectScreenshotStats(WebDriver driver) {
        try {
            byte[] bytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            if (bytes == null || bytes.length == 0) {
                return BrowserRuntimeObservation.ScreenshotStats.empty();
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return BrowserRuntimeObservation.ScreenshotStats.empty();
            }
            return analyzeImage(image);
        } catch (Exception exception) {
            log.warn("浏览器截图证据采集失败，保留 console/network 观测: error={}",
                    LogExceptionSanitizer.sanitizeMessage(exception));
            return BrowserRuntimeObservation.ScreenshotStats.empty();
        }
    }

    /** 返回分析{@code Image}。 */
    static BrowserRuntimeObservation.ScreenshotStats analyzeImage(BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return BrowserRuntimeObservation.ScreenshotStats.empty();
        }
        int stepX = Math.max(1, image.getWidth() / 40);
        int stepY = Math.max(1, image.getHeight() / 24);
        Set<Integer> colorBuckets = new HashSet<>();
        int minimumLuminance = 255;
        int maximumLuminance = 0;
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >>> 16) & 0xff;
                int green = (rgb >>> 8) & 0xff;
                int blue = rgb & 0xff;
                colorBuckets.add(((red >>> 5) << 6) | ((green >>> 5) << 3) | (blue >>> 5));
                int luminance = (299 * red + 587 * green + 114 * blue) / 1_000;
                minimumLuminance = Math.min(minimumLuminance, luminance);
                maximumLuminance = Math.max(maximumLuminance, luminance);
            }
        }
        return new BrowserRuntimeObservation.ScreenshotStats(
                true,
                image.getWidth(),
                image.getHeight(),
                colorBuckets.size(),
                maximumLuminance - minimumLuminance
        );
    }

    /** 返回{@code string}列表。 */
    private List<String> stringList(JSONObject page, String key) {
        JSONArray values = page.getJSONArray(key);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                result.add(String.valueOf(value));
            }
        }
        return List.copyOf(result);
    }

    /** 关闭驱动并释放资源。 */
    private void closeDriver(WebDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            driver.quit();
        } catch (RuntimeException exception) {
            log.warn("Failed to close browser runtime probe session, error={}",
                    LogExceptionSanitizer.sanitizeMessage(exception));
        }
    }
}
