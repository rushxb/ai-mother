package com.rush.rushaicodemother.infrastructure.screenshot.selenium;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.config.ScreenshotProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;

import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Level;

/**
 * 基于部署环境显式驱动路径创建独立 Chrome 会话。
 *
 * <p>每次调用都创建新会话，调用方必须在 {@code finally} 中执行 {@link WebDriver#quit()}。</p>
 */
@Slf4j
public final class SeleniumChromeDriverFactory {

    private final ScreenshotProperties properties;

    public SeleniumChromeDriverFactory(ScreenshotProperties properties) {
        this.properties = properties;
    }

    public WebDriver createScreenshotDriver() {
        return createDriver(false, false);
    }

    public WebDriver createDiagnosticDriver() {
        return createDriver(true, false);
    }

    public WebDriver createIsolatedDiagnosticDriver() {
        return createDriver(true, true);
    }

    /** 创建驱动。 */
    private WebDriver createDriver(boolean browserLoggingEnabled, boolean loopbackOnly) {
        Path driverExecutable = properties.requireChromeDriverPath();
        ChromeDriverService driverService = new ChromeDriverService.Builder()
                .usingDriverExecutable(driverExecutable.toFile())
                .usingAnyFreePort()
                .build();
        ChromeOptions options = buildOptions(browserLoggingEnabled, loopbackOnly);
        try {
            WebDriver driver = new ChromeDriver(driverService, options);
            configureTimeouts(driver, properties.getPageLoadTimeout());
            return driver;
        } catch (RuntimeException exception) {
            log.error("创建 Chrome 浏览器会话失败", LogExceptionSanitizer.sanitize(exception));
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "创建浏览器会话失败，请检查 Chrome 与 ChromeDriver 版本及运行权限",
                    exception
            );
        }
    }

    /** 构建并返回{@code Options}。 */
    private ChromeOptions buildOptions(boolean browserLoggingEnabled, boolean loopbackOnly) {
        ChromeOptions options = new ChromeOptions();
        Path chromeBinary = properties.resolveChromeBinaryPath();
        if (chromeBinary != null) {
            options.setBinary(chromeBinary.toFile());
        }
        options.addArguments("--headless=new");
        options.addArguments("--disable-gpu");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-extensions");
        options.addArguments("--window-size="
                + properties.getViewportWidth() + "," + properties.getViewportHeight());
        if (loopbackOnly) {
            options.addArguments("--disable-background-networking");
            options.addArguments("--disable-component-update");
            options.addArguments("--disable-default-apps");
            options.addArguments("--disable-quic");
            options.addArguments("--disable-sync");
            options.addArguments("--metrics-recording-only");
            options.addArguments("--no-first-run");
            options.addArguments("--proxy-server=http://127.0.0.1:9");
            options.addArguments("--proxy-bypass-list=localhost;127.0.0.1;[::1]");
            options.addArguments(
                    "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE localhost, EXCLUDE 127.0.0.1, EXCLUDE ::1"
            );
        }
        if (properties.isNoSandbox()) {
            options.addArguments("--no-sandbox");
        }
        if (browserLoggingEnabled) {
            LoggingPreferences loggingPreferences = new LoggingPreferences();
            loggingPreferences.enable(LogType.BROWSER, Level.ALL);
            options.setCapability("goog:loggingPrefs", loggingPreferences);
        }
        return options;
    }

    private void configureTimeouts(WebDriver driver, Duration pageLoadTimeout) {
        driver.manage().timeouts().pageLoadTimeout(pageLoadTimeout);
        driver.manage().timeouts().scriptTimeout(pageLoadTimeout);
        driver.manage().timeouts().implicitlyWait(Duration.ZERO);
    }
}
