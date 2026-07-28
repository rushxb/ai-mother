package com.rush.rushaicodemother.infrastructure.screenshot.selenium;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.img.ImgUtil;
import com.rush.rushaicodemother.config.ScreenshotProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.service.screenshot.WebPageScreenshotRenderer;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Selenium 网页截图渲染器。 */
@Slf4j
public final class SeleniumWebPageScreenshotRenderer implements WebPageScreenshotRenderer {

    private final SeleniumChromeDriverFactory driverFactory;
    private final ScreenshotProperties properties;

    public SeleniumWebPageScreenshotRenderer(SeleniumChromeDriverFactory driverFactory,
                                             ScreenshotProperties properties) {
        this.driverFactory = driverFactory;
        this.properties = properties;
    }

    /**
 * 渲染{@code Selenium}Web页面截图渲染器。
 *
 * @param targetUri {@code targetUri} 对应的调用参数
 * @param workspace 工作区
 * @return 解析后的{@code Selenium}Web页面截图渲染器路径
 */
    @Override
    public Path render(URI targetUri, Path workspace) {
        Path normalizedWorkspace = requireSafeWorkspace(workspace);
        WebDriver driver = null;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            driver = driverFactory.createScreenshotDriver();
            driver.get(targetUri.toASCIIString());
            waitForPageReady(driver);
            byte[] screenshotBytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            if (screenshotBytes == null || screenshotBytes.length == 0) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "浏览器未生成有效截图数据");
            }
            Path sourceImage = normalizedWorkspace.resolve("source.png");
            Path compressedImage = normalizedWorkspace.resolve("screenshot.jpg");
            Files.write(sourceImage, screenshotBytes);
            ImgUtil.compress(
                    sourceImage.toFile(),
                    compressedImage.toFile(),
                    properties.getCompressionQuality()
            );
            Files.deleteIfExists(sourceImage);
            if (!Files.isRegularFile(compressedImage, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(compressedImage)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "截图压缩结果不存在");
            }
            return compressedImage;
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "网页截图任务已中断", exception);
        } catch (Exception exception) {
            log.error("网页截图渲染失败，target={}", targetUri, LogExceptionSanitizer.sanitize(exception));
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成网页截图失败", exception);
        } finally {
            closeDriver(driver);
        }
    }

    /** 校验并返回有效的安全工作区。 */
    private Path requireSafeWorkspace(Path workspace) {
        if (workspace == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "截图工作目录不能为空");
        }
        Path normalized = workspace.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "截图工作目录不安全或不存在");
        }
        return normalized;
    }

    private void waitForPageReady(WebDriver driver) throws InterruptedException {
        WebDriverWait wait = new WebDriverWait(driver, properties.getReadyStateTimeout());
        wait.until(currentDriver -> "complete".equals(
                ((JavascriptExecutor) currentDriver).executeScript("return document.readyState")
        ));
        if (!properties.getPostLoadDelay().isZero()) {
            Thread.sleep(properties.getPostLoadDelay().toMillis());
        }
    }

    /** 关闭驱动并释放资源。 */
    private void closeDriver(WebDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            driver.quit();
        } catch (RuntimeException exception) {
            log.warn("关闭 Chrome 浏览器会话失败", LogExceptionSanitizer.sanitize(exception));
        }
    }
}
