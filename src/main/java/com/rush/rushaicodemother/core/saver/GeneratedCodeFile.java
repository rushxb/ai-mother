package com.rush.rushaicodemother.core.saver;

/** 一个待持久化的模型生成文件；空内容表示删除旧文件。 */
public record GeneratedCodeFile(String relativePath, String content) {

    public GeneratedCodeFile {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("生成文件相对路径不能为空");
        }
        relativePath = relativePath.trim();
    }
}
