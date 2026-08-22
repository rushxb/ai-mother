package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.nio.file.Path;

/**
 * 解析应用当前对用户可见的生成制品目录。
 *
 * <p>调用方不关心制品来自遗留目录还是版本化发布目录，解析策略可以随存储模型扩展。</p>
 */
@FunctionalInterface
public interface CurrentGeneratedArtifactResolver {

    /** 返回指定应用与工程类型当前生效的只读制品根目录。 */
    Path resolve(Long appId, CodeGenTypeEnum codeGenType);
}
