package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeGrader;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeProbe;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/** 对生成的 Vue 项目执行真实浏览器运行时与视觉评分。 */
@Component
public class BrowserGenerationBenchmarkRuntimeGrader implements GenerationBenchmarkRuntimeGrader {

    private static final String GRADER_ID = "browser_preview";
    private static final List<GenerationBenchmarkQualityDimension> DIMENSIONS = List.of(
            GenerationBenchmarkQualityDimension.RUNTIME,
            GenerationBenchmarkQualityDimension.VISUAL
    );

    private final GenerationBenchmarkBrowserProperties properties;
    private final BrowserGenerationRuntimeEvaluator evaluator;

    @Autowired
    public BrowserGenerationBenchmarkRuntimeGrader(
            GenerationBenchmarkBrowserProperties properties,
            BrowserGenerationRuntimeEvaluator evaluator
    ) {
        this.properties = properties;
        this.evaluator = evaluator;
    }

    /**
 * 创建浏览器生成基准测试运行时{@code Grader}实例并完成必要的依赖和初始状态设置。
 *
 * @param properties 配置属性
 * @param devServerManager 开发服务器管理器
 * @param browserRuntimeProbe {@code browserRuntimeProbe} 对应的调用参数
 */
    public BrowserGenerationBenchmarkRuntimeGrader(
            GenerationBenchmarkBrowserProperties properties,
            DevServerManager devServerManager,
            BrowserRuntimeProbe browserRuntimeProbe
    ) {
        this(
                properties,
                new BrowserGenerationRuntimeEvaluator(
                        properties,
                        devServerManager,
                        browserRuntimeProbe
                )
        );
    }

    @Override
    public String id() {
        return GRADER_ID;
    }

    @Override
    public List<GenerationBenchmarkQualityDimension> dimensions() {
        return DIMENSIONS;
    }

    /**
 * 返回{@code supports}。
 *
 * @param task 任务
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean supports(GenerationBenchmarkTask task) {
        return properties.isEnabled()
                && task != null
                && CodeGenTypeEnum.getEnumByValue(task.codeGenType()) == CodeGenTypeEnum.VUE_PROJECT;
    }

    @Override
    public List<GenerationBenchmarkRuleResult> evaluate(GenerationBenchmarkRuntimeContext context) {
        return evaluator.evaluate(context, CodeGenTypeEnum.VUE_PROJECT, null);
    }
}
