package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 约束 Vue 生产验收与 Benchmark 复用同一套 dev-server/browser verifier。
 *
 * <p>两条链路允许采用不同验收策略，但禁止各自维护进程生命周期或浏览器探测实现，
 * 否则评测结论会逐步偏离用户真正经历的生产链路。</p>
 */
class VueRuntimeVerifierParityArchitectureTest {

    private static final Path MAIN_ROOT = Path.of("src", "main", "java",
            "com", "rush", "rushaicodemother");
    private static final Path BENCHMARK_EVALUATOR = MAIN_ROOT.resolve(Path.of(
            "orchestration", "benchmark", "runtime", "BrowserGenerationRuntimeEvaluator.java"));
    private static final Path PRODUCTION_ADAPTER = MAIN_ROOT.resolve(Path.of(
            "orchestration", "heavy", "VueProjectValidationAdapter.java"));

    @Test
    void productionAndBenchmarkMustShareDevServerValidationImplementation() throws Exception {
        String benchmarkSource = Files.readString(BENCHMARK_EVALUATOR);
        String productionSource = Files.readString(PRODUCTION_ADAPTER);

        assertThat(benchmarkSource)
                .contains("DevServerValidationService")
                .contains("BrowserRuntimeValidationPolicy.benchmark(")
                .doesNotContain("DevServerManager", "BrowserRuntimeVerifier",
                        "startDevServer(", "stopDevServer(");
        assertThat(productionSource)
                .contains("DevServerValidationService")
                .contains("BrowserRuntimeValidationPolicy.productionRuntime()");
    }
}
