package com.rush.rushaicodemother.service.dependency;

import java.nio.file.Path;

/**
 * 项目依赖供给模块。
 *
 * <p>调用方只关心“依赖可用”这一结果，不需要了解 pnpm 重试、文件修复和进程生命周期细节。</p>
 */
public interface ProjectDependencyInstaller {

    /** 确保项目依赖完整且可被运行时加载。 */
    DependencyInstallResult ensureInstalled(Path projectDirectory);

    /**
     * Ensures dependencies for a managed generation task. Implementations may use the task id for
     * deadline and cancellation propagation; legacy callers remain supported by the base method.
     */
    default DependencyInstallResult ensureInstalled(Path projectDirectory, String taskId) {
        return ensureInstalled(projectDirectory);
    }

    /** 取消指定项目当前正在执行的依赖安装。 */
    boolean cancel(Path projectDirectory);
}
