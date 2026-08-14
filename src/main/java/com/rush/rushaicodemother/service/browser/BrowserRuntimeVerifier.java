package com.rush.rushaicodemother.service.browser;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 将受控浏览器采集结果映射为生产与 Benchmark 共享的确定性验证事实。 */
@Slf4j
@Service
public class BrowserRuntimeVerifier {

    private final BrowserRuntimeProbe browserRuntimeProbe;

    public BrowserRuntimeVerifier(BrowserRuntimeProbe browserRuntimeProbe) {
        this.browserRuntimeProbe = browserRuntimeProbe;
    }

    public BrowserRuntimeValidationResult verify(
            URI targetUri,
            BrowserRuntimeValidationPolicy policy
    ) {
        long startedAt = System.nanoTime();
        BrowserRuntimeValidationPolicy resolvedPolicy = policy == null
                ? BrowserRuntimeValidationPolicy.productionRuntime()
                : policy;
        if (targetUri == null) {
            return BrowserRuntimeValidationResult.failed(
                    0,
                    "browser_target_missing",
                    resolvedPolicy.requireVisualEvidence()
            );
        }
        try {
            BrowserRuntimeObservation observation = browserRuntimeProbe.inspect(
                    targetUri,
                    resolvedPolicy.settleDelay()
            );
            if (observation == null) {
                return BrowserRuntimeValidationResult.failed(
                        elapsedSince(startedAt),
                        "browser_observation_missing",
                        resolvedPolicy.requireVisualEvidence()
                );
            }
            return mapObservation(observation, resolvedPolicy, elapsedSince(startedAt));
        } catch (RuntimeException exception) {
            if (Thread.currentThread().isInterrupted()) {
                throw exception;
            }
            log.warn("浏览器运行时验证执行失败: error={}",
                    LogExceptionSanitizer.sanitizeMessage(exception));
            return BrowserRuntimeValidationResult.failed(
                    elapsedSince(startedAt),
                    "browser_probe_failed",
                    resolvedPolicy.requireVisualEvidence()
            );
        }
    }

    private BrowserRuntimeValidationResult mapObservation(
            BrowserRuntimeObservation observation,
            BrowserRuntimeValidationPolicy policy,
            long durationMs
    ) {
        List<String> runtimeViolations = new ArrayList<>();
        if (!LoopbackBrowserTargetPolicy.sameOrigin(
                observation.requestedUri(), observation.finalUri())) {
            runtimeViolations.add("preview_origin_changed");
        }
        if (!"complete".equalsIgnoreCase(observation.readyState())) {
            runtimeViolations.add("document_not_ready");
        }
        if (observation.viteErrorOverlayPresent()) {
            runtimeViolations.add("vite_error_overlay_present");
        }
        if (observation.hasFatalConsoleError()) {
            runtimeViolations.add("browser_console_error");
        }
        if (!observation.networkEvidence().captured()) {
            runtimeViolations.add("browser_network_evidence_missing");
        } else if (observation.hasNetworkFailure()) {
            runtimeViolations.add("browser_network_error");
        }
        if (observation.looksLikeErrorPage()) {
            runtimeViolations.add("error_page_rendered");
        }
        if (!observation.appNodeExists()) {
            runtimeViolations.add("app_mount_missing");
        } else if (observation.appNodeChildCount() == 0 && observation.bodyTextLength() == 0) {
            runtimeViolations.add("app_render_empty");
        }

        List<String> visualViolations = gradeVisual(observation);
        List<String> diagnostics = new ArrayList<>();
        observation.consoleMessages().stream()
                .filter(BrowserRuntimeObservation.ConsoleMessage::fatal)
                .map(BrowserRuntimeObservation.ConsoleMessage::displayValue)
                .forEach(diagnostics::add);
        observation.networkEvidence().failures().stream()
                .filter(BrowserRuntimeObservation.NetworkFailure::fatal)
                .map(BrowserRuntimeObservation.NetworkFailure::displayValue)
                .forEach(diagnostics::add);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runtimeKind", "browser_console_network");
        details.put("requestedUri", observation.requestedUri().toASCIIString());
        details.put("finalUri", observation.finalUri().toASCIIString());
        details.put("consoleMessageCount", observation.consoleMessages().size());
        details.put("networkEvidenceCaptured", observation.networkEvidence().captured());
        details.put("networkFailureCount", observation.networkEvidence().failures().size());
        details.put("screenshotCaptured", observation.screenshot().captured());
        return new BrowserRuntimeValidationResult(
                durationMs,
                policy.requireVisualEvidence(),
                runtimeViolations,
                visualViolations,
                diagnostics,
                details
        );
    }

    private List<String> gradeVisual(BrowserRuntimeObservation observation) {
        List<String> violations = new ArrayList<>();
        BrowserRuntimeObservation.ScreenshotStats screenshot = observation.screenshot();
        if (!screenshot.captured()) {
            violations.add("screenshot_missing");
        } else {
            if (screenshot.width() < 320 || screenshot.height() < 240) {
                violations.add("screenshot_dimensions_invalid");
            }
            if (screenshot.nearUniform()) {
                violations.add("screenshot_near_uniform");
            }
        }
        if (observation.documentWidth() < 1 || observation.documentHeight() < 1) {
            violations.add("document_has_no_visual_area");
        }
        if (observation.visibleElementCount() < 2
                || observation.bodyTextLength() == 0 && observation.visibleElementCount() < 3) {
            violations.add("visible_content_empty");
        }
        if (observation.viteErrorOverlayPresent() || observation.looksLikeErrorPage()) {
            violations.add("visual_error_state_rendered");
        }
        return violations;
    }

    private long elapsedSince(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
