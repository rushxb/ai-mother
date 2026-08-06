package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 浏览器截图运行时配置。
 *
 * <p>ChromeDriver 必须由部署环境显式提供，应用不会在运行期间联网下载驱动。</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.screenshot")
public class ScreenshotProperties {

    public static final int MAX_CONCURRENCY = 2;
    public static final int QUEUE_CAPACITY = 16;
    public static final int VIEWPORT_WIDTH = 1600;
    public static final int VIEWPORT_HEIGHT = 900;
    public static final Duration PAGE_LOAD_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration READY_STATE_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration POST_LOAD_DELAY = Duration.ofSeconds(2);
    public static final float COMPRESSION_QUALITY = 0.3F;

    private boolean enabled;

    private String chromeDriverPath;

    private String chromeBinaryPath;

    private Path workDirectory = Path.of(AppConstant.SCREENSHOT_ROOT_DIR);

    private int maxConcurrency = MAX_CONCURRENCY;

    private int queueCapacity = QUEUE_CAPACITY;

    private int viewportWidth = VIEWPORT_WIDTH;

    private int viewportHeight = VIEWPORT_HEIGHT;

    private Duration pageLoadTimeout = PAGE_LOAD_TIMEOUT;

    private Duration readyStateTimeout = READY_STATE_TIMEOUT;

    private Duration postLoadDelay = POST_LOAD_DELAY;

    private float compressionQuality = COMPRESSION_QUALITY;

    private boolean noSandbox;

    @AssertTrue(message = "启用截图时必须配置存在且非符号链接的 ChromeDriver 普通文件")
    public boolean isEnabledChromeDriverValid() {
        return !enabled || isSafeRegularFile(chromeDriverPath);
    }

    @AssertTrue(message = "Chrome 浏览器可执行文件必须是存在且非符号链接的普通文件")
    public boolean isChromeBinaryValid() {
        return !hasText(chromeBinaryPath) || isSafeRegularFile(chromeBinaryPath);
    }

    /**
 * 判断运行时限制是否有效。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "截图并发、队列、视口、超时和压缩质量配置必须在安全范围内")
    public boolean isRuntimeLimitsValid() {
        return workDirectory != null
                && maxConcurrency > 0
                && maxConcurrency <= 16
                && queueCapacity >= 0
                && queueCapacity <= 10_000
                && viewportWidth >= 320
                && viewportWidth <= 7680
                && viewportHeight >= 240
                && viewportHeight <= 4320
                && isPositive(pageLoadTimeout)
                && isPositive(readyStateTimeout)
                && postLoadDelay != null
                && !postLoadDelay.isNegative()
                && postLoadDelay.compareTo(Duration.ofSeconds(30)) <= 0
                && compressionQuality > 0.0F
                && compressionQuality <= 1.0F;
    }

    /**
 * 校验并返回有效的Chrome驱动路径。
 *
 * @return 解析后的Chrome驱动路径路径
 */
    public Path requireChromeDriverPath() {
        if (!isSafeRegularFile(chromeDriverPath)) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "浏览器自动化未配置可用的 ChromeDriver"
            );
        }
        return Path.of(chromeDriverPath.trim()).toAbsolutePath().normalize();
    }

    /**
 * 根据当前上下文解析Chrome可执行文件路径。
 *
 * @return 解析后的Chrome可执行文件路径路径
 */
    public Path resolveChromeBinaryPath() {
        if (!hasText(chromeBinaryPath)) {
            return null;
        }
        if (!isSafeRegularFile(chromeBinaryPath)) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "浏览器自动化配置的 Chrome 可执行文件不可用"
            );
        }
        return Path.of(chromeBinaryPath.trim()).toAbsolutePath().normalize();
    }

    /** 判断安全常规文件是否满足约束。 */
    private boolean isSafeRegularFile(String rawPath) {
        if (!hasText(rawPath)) {
            return false;
        }
        try {
            Path path = Path.of(rawPath.trim()).toAbsolutePath().normalize();
            return !Files.isSymbolicLink(path)
                    && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
