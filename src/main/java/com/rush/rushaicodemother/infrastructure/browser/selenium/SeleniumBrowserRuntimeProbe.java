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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Selenium implementation with loopback-only target validation and bounded evidence capture. */
@Slf4j
@Component
public class SeleniumBrowserRuntimeProbe implements BrowserRuntimeProbe {

    private static final int MAX_CONSOLE_MESSAGES = 50;
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

    private BrowserRuntimeObservation.ScreenshotStats collectScreenshotStats(WebDriver driver) {
        byte[] bytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
        if (bytes == null || bytes.length == 0) {
            return BrowserRuntimeObservation.ScreenshotStats.empty();
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return BrowserRuntimeObservation.ScreenshotStats.empty();
            }
            return analyzeImage(image);
        } catch (Exception exception) {
            throw new IllegalStateException("unable to analyze browser screenshot", exception);
        }
    }

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
