package com.rush.rushaicodemother.orchestration.benchmark;

import java.util.List;

/** 在最终用户响应中执行固定字符串匹配，不接受正则或可执行表达式。 */
public record GenerationBenchmarkResponseAssertion(
        String id,
        List<String> allOf,
        List<String> anyOf,
        List<String> noneOf
) {
    public GenerationBenchmarkResponseAssertion {
        allOf = allOf == null ? List.of() : List.copyOf(allOf);
        anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
        noneOf = noneOf == null ? List.of() : List.copyOf(noneOf);
    }
}
