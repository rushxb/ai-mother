package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeObservation;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeProbe;
import com.rush.rushaicodemother.service.browser.LoopbackBrowserTargetPolicy;
import com.rush.rushaicodemother.service.devserver.DevServerErrorCollector;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import com.rush.rushaicodemother.service.devserver.DevServerStartOptions;
import com.rush.rushaicodemother.service.devserver.DevServerStartResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** 统一执行 Vue 与全栈前端的浏览器运行时和视觉评分。 */
@Slf4j
@Component
public class BrowserGenerationRuntimeEvaluator {

    private static final String RUNTIME_RULE_ID = "browser_runtime";
    private static final String VISUAL_RULE_ID = "browser_visual";

    private final GenerationBenchmarkBrowserProperties properties;
    private final DevServerManager devServerManager;
    private final BrowserRuntimeProbe browserRuntimeProbe;

    public BrowserGenerationRuntimeEvaluator(
            GenerationBenchmarkBrowserProperties properties,
            DevServerManager devServerManager,
            BrowserRuntimeProbe browserRuntimeProbe
    ) {
        this.properties = properties;
        this.devServerManager = devServerManager;
        this.browserRuntimeProbe = browserRuntimeProbe;
    }

    /**
 * 返回{@code evaluate}。
 *
 * @param context 执行上下文
 * @param codeGenType 代码生成类型
 * @param startOptions 待处理的 {@code startOptions} 集合
 * @return 浏览器生成运行时{@code Evaluator}集合
 */
    public List<GenerationBenchmarkRuleResult> evaluate(
            GenerationBenchmarkRuntimeContext context,
            CodeGenTypeEnum codeGenType,
            DevServerStartOptions startOptions
    ) {
        long appId = context.workspace().appId();
        DevServerErrorCollector errorCollector = new DevServerErrorCollector();
        DevServerStartResult startResult = null;
        devServerManager.registerErrorCollector(appId, errorCollector);
        try {
            App app = new App();
            app.setId(appId);
            app.setCodeGenType(codeGenType.getValue());
            startResult = startOptions == null
                    ? devServerManager.startDevServer(app, context.userId())
                    : devServerManager.startDevServer(app, context.userId(), startOptions);
            URI target = URI.create("http://127.0.0.1:" + startResult.port() + "/");
            BrowserRuntimeObservation observation = browserRuntimeProbe.inspect(
                    target,
                    properties.getSettleDelay()
            );
            return List.of(
                    gradeRuntime(observation, errorCollector),
                    gradeVisual(observation)
            );
        } finally {
            stopOwnedSession(appId, startResult);
            devServerManager.unregisterErrorCollector(appId, errorCollector);
        }
    }

    /** 返回{@code grade}运行时。 */
    private GenerationBenchmarkRuleResult gradeRuntime(
            BrowserRuntimeObservation observation,
            DevServerErrorCollector errorCollector
    ) {
        List<String> violations = new ArrayList<>();
        if (!LoopbackBrowserTargetPolicy.sameOrigin(observation.requestedUri(), observation.finalUri())) {
            violations.add("preview_origin_changed");
        }
        if (!"complete".equalsIgnoreCase(observation.readyState())) {
            violations.add("document_not_ready");
        }
        if (errorCollector.hasCriticalError()) {
            violations.add("dev_server_critical_error");
        }
        if (observation.viteErrorOverlayPresent()) {
            violations.add("vite_error_overlay_present");
        }
        if (observation.hasFatalConsoleError()) {
            violations.add("browser_console_error");
        }
        if (observation.looksLikeErrorPage()) {
            violations.add("error_page_rendered");
        }
        if (!observation.appNodeExists()) {
            violations.add("app_mount_missing");
        } else if (observation.appNodeChildCount() == 0 && observation.bodyTextLength() == 0) {
            violations.add("app_render_empty");
        }
        return result(RUNTIME_RULE_ID, GenerationBenchmarkQualityDimension.RUNTIME, violations);
    }

    /** 返回{@code grade}{@code Visual}。 */
    private GenerationBenchmarkRuleResult gradeVisual(BrowserRuntimeObservation observation) {
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
        return result(VISUAL_RULE_ID, GenerationBenchmarkQualityDimension.VISUAL, violations);
    }

    private GenerationBenchmarkRuleResult result(
            String ruleId,
            GenerationBenchmarkQualityDimension dimension,
            List<String> violations
    ) {
        return new GenerationBenchmarkRuleResult(
                ruleId,
                dimension,
                violations.isEmpty(),
                violations,
                0
        );
    }

    /** 停止{@code Owned}会话。 */
    private void stopOwnedSession(long appId, DevServerStartResult startResult) {
        if (startResult == null || !startResult.startedByCaller()) {
            return;
        }
        try {
            devServerManager.stopDevServer(appId);
        } catch (RuntimeException exception) {
            log.warn("清理基准测试浏览器 Dev Server 失败: appId={}, error={}",
                    appId, LogExceptionSanitizer.sanitizeMessage(exception));
        }
    }
}
