package com.rush.rushaicodemother.orchestration.codegraph;

/**
 * 代码图符号的不可变数据载体。
 */
public record CodeGraphSymbol(
        String name,
        String kind,
        String relativePath
) {
}
