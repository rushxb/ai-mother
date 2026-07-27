package com.rush.rushaicodemother.orchestration.benchmark;

import java.util.List;

/** 在限定源码路径内执行固定字符串匹配，不接受正则或可执行表达式。 */
public record GenerationBenchmarkSourceAssertion(
        String id,
        GenerationBenchmarkSourceRoot root,
        List<String> paths,
        List<String> allOf,
        List<String> anyOf,
        List<String> noneOf
) {
    public GenerationBenchmarkSourceAssertion {
        paths = paths == null ? List.of() : List.copyOf(paths);
        allOf = allOf == null ? List.of() : List.copyOf(allOf);
        anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
        noneOf = noneOf == null ? List.of() : List.copyOf(noneOf);
    }
}
