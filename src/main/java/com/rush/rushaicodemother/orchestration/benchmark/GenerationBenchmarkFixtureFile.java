package com.rush.rushaicodemother.orchestration.benchmark;

/** 评测开始前写入受控工作区的确定性源码夹具。 */
public record GenerationBenchmarkFixtureFile(
        GenerationBenchmarkSourceRoot root,
        String path,
        String content
) {
}
