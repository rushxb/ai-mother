package com.rush.rushaicodemother.service.browser;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** 从一个受控本地预览收集的有界浏览器证据。 */
public record BrowserRuntimeObservation(
        URI requestedUri,
        URI finalUri,
        String title,
        String readyState,
        int bodyTextLength,
        int bodyChildCount,
        boolean appNodeExists,
        int appNodeChildCount,
        int visibleElementCount,
        int documentWidth,
        int documentHeight,
        boolean viteErrorOverlayPresent,
        String firstText,
        String firstHeading,
        List<String> scriptUrls,
        List<String> stylesheetUrls,
        List<ConsoleMessage> consoleMessages,
        NetworkEvidence networkEvidence,
        ScreenshotStats screenshot
) {

    /** 创建浏览器运行时观测实例并完成必要的依赖和初始状态设置。 */
    public BrowserRuntimeObservation {
        if (requestedUri == null || finalUri == null) {
            throw new IllegalArgumentException("browser observation URIs are required");
        }
        title = bounded(title, 256);
        readyState = bounded(readyState, 32);
        bodyTextLength = Math.max(0, bodyTextLength);
        bodyChildCount = Math.max(0, bodyChildCount);
        appNodeChildCount = Math.max(0, appNodeChildCount);
        visibleElementCount = Math.max(0, visibleElementCount);
        documentWidth = Math.max(0, documentWidth);
        documentHeight = Math.max(0, documentHeight);
        firstText = bounded(firstText, 512);
        firstHeading = bounded(firstHeading, 256);
        scriptUrls = boundedList(scriptUrls, 20, 512);
        stylesheetUrls = boundedList(stylesheetUrls, 20, 512);
        consoleMessages = consoleMessages == null ? List.of() : consoleMessages.stream()
                .filter(message -> message != null)
                .limit(50)
                .toList();
        networkEvidence = networkEvidence == null ? NetworkEvidence.unavailable() : networkEvidence;
        screenshot = screenshot == null ? ScreenshotStats.empty() : screenshot;
    }

    public boolean hasFatalConsoleError() {
        return consoleMessages.stream().anyMatch(ConsoleMessage::fatal);
    }

    public boolean hasNetworkFailure() {
        return networkEvidence.failures().stream().anyMatch(NetworkFailure::fatal);
    }

    /**
 * 返回{@code looks}{@code Like}错误页面。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean looksLikeErrorPage() {
        String normalizedTitle = title.toLowerCase(Locale.ROOT);
        String normalizedHeading = firstHeading.toLowerCase(Locale.ROOT);
        String normalizedText = firstText.toLowerCase(Locale.ROOT);
        String evidence = normalizedTitle + " " + normalizedHeading + " " + normalizedText;
        return normalizedTitle.matches(".*\\b(?:404|500)\\b.*")
                || normalizedHeading.matches("^\\s*(?:404|500)\\b.*")
                || normalizedText.matches("^\\s*(?:404|500)\\b.*")
                || evidence.contains("500 internal server error")
                || evidence.contains("internal server error")
                || evidence.contains("cannot get /")
                || evidence.contains("page not found")
                || evidence.contains("页面不存在")
                || evidence.contains("服务器错误");
    }

    private static List<String> boundedList(List<String> values, int maxItems, int maxChars) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(maxItems)
                .map(value -> bounded(value, maxChars))
                .toList();
    }

    private static String bounded(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.substring(0, Math.min(maxChars, normalized.length()));
    }

    public record ConsoleMessage(String level, String message) {

        public ConsoleMessage {
            level = bounded(level, 16).toUpperCase(Locale.ROOT);
            message = bounded(message, 1_000);
        }

        /**
 * 返回{@code fatal}。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
        public boolean fatal() {
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("favicon.ico")) {
                return false;
            }
            return "SEVERE".equals(level)
                    || normalized.contains("uncaught ")
                    || normalized.contains("referenceerror")
                    || normalized.contains("typeerror")
                    || normalized.contains("syntaxerror")
                    || normalized.contains("chunkloaderror")
                    || normalized.contains("failed to fetch dynamically imported module")
                    || normalized.contains("failed to load resource")
                    || normalized.contains("net::err_");
        }

        public String displayValue() {
            return "UNTRUSTED_BROWSER_LOG " + level + " | " + message;
        }
    }

    /** Chrome DevTools performance log 中有界的网络失败证据。 */
    public record NetworkEvidence(boolean captured, List<NetworkFailure> failures) {

        public NetworkEvidence {
            failures = failures == null ? List.of() : failures.stream()
                    .filter(failure -> failure != null)
                    .distinct()
                    .limit(50)
                    .toList();
        }

        public static NetworkEvidence captured(List<NetworkFailure> failures) {
            return new NetworkEvidence(true, failures);
        }

        public static NetworkEvidence unavailable() {
            return new NetworkEvidence(false, List.of());
        }
    }

    /** 单次失败响应或加载失败；状态码为 0 表示请求未获得 HTTP 响应。 */
    public record NetworkFailure(String url, int status, String reason) {

        public NetworkFailure {
            url = bounded(url, 512);
            status = Math.max(0, Math.min(599, status));
            reason = bounded(reason, 256);
        }

        public boolean fatal() {
            if (url.toLowerCase(Locale.ROOT).contains("favicon.ico")) {
                return false;
            }
            return status == 0 || status >= 400;
        }

        public String displayValue() {
            return "UNTRUSTED_BROWSER_NETWORK status=" + status
                    + " reason=" + reason + " url=" + url;
        }
    }

    public record ScreenshotStats(
            boolean captured,
            int width,
            int height,
            int sampledColorBuckets,
            int luminanceRange
    ) {

        public ScreenshotStats {
            width = Math.max(0, width);
            height = Math.max(0, height);
            sampledColorBuckets = Math.max(0, sampledColorBuckets);
            luminanceRange = Math.max(0, Math.min(255, luminanceRange));
        }

        public static ScreenshotStats empty() {
            return new ScreenshotStats(false, 0, 0, 0, 0);
        }

        public boolean nearUniform() {
            return !captured || sampledColorBuckets < 3 || luminanceRange < 4;
        }
    }
}
