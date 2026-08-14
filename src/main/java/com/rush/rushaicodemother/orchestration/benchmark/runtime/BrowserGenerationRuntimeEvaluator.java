package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationPolicy;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationResult;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeVerifier;
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
    private final BrowserRuntimeVerifier browserRuntimeVerifier;

    public BrowserGenerationRuntimeEvaluator(
            GenerationBenchmarkBrowserProperties properties,
            DevServerManager devServerManager,
            BrowserRuntimeVerifier browserRuntimeVerifier
    ) {
        this.properties = properties;
        this.devServerManager = devServerManager;
        this.browserRuntimeVerifier = browserRuntimeVerifier;
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
            BrowserRuntimeValidationResult validation = browserRuntimeVerifier.verify(
                    target,
                    BrowserRuntimeValidationPolicy.benchmark(properties.getSettleDelay())
            );
            return List.of(
                    gradeRuntime(validation, errorCollector),
                    result(
                            VISUAL_RULE_ID,
                            GenerationBenchmarkQualityDimension.VISUAL,
                            validation.visualViolations()
                    )
            );
        } finally {
            stopOwnedSession(appId, startResult);
            devServerManager.unregisterErrorCollector(appId, errorCollector);
        }
    }

    /** 返回{@code grade}运行时。 */
    private GenerationBenchmarkRuleResult gradeRuntime(
            BrowserRuntimeValidationResult validation,
            DevServerErrorCollector errorCollector
    ) {
        List<String> violations = new ArrayList<>(validation.runtimeViolations());
        if (errorCollector.hasCriticalError()) {
            violations.add("dev_server_critical_error");
        }
        return result(RUNTIME_RULE_ID, GenerationBenchmarkQualityDimension.RUNTIME, violations);
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
