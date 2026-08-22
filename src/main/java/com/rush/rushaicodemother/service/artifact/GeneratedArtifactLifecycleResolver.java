package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.nio.file.Path;
import java.util.List;

/**
 * 解析应用生成制品的当前版本和生命周期资源边界。
 *
 * <p>调用方不关心制品来自遗留目录、版本化发布目录还是执行隔离区，
 * 存储布局可以通过替换实现扩展，业务服务不直接依赖目录命名。</p>
 */
public interface GeneratedArtifactLifecycleResolver {

    /** 返回指定应用与工程类型当前生效的只读制品根目录。 */
    Path resolveCurrent(Long appId, CodeGenTypeEnum codeGenType);

    /**
     * 返回由生成工作区模块拥有、且应随应用删除的目录。
     *
     * <p>实现必须只返回应用级目录，不能返回共享存储根或其他应用目录。</p>
     */
    List<Path> deletionRoots(Long appId);
}
