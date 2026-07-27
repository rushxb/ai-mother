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
     * 确保托管生成任务的依赖性。实现可以使用任务 id
     * 截止日期和取消传播；旧调用者仍然受基本方法支持。
     */
    default DependencyInstallResult ensureInstalled(Path projectDirectory, String taskId) {
        return ensureInstalled(projectDirectory, taskId, DependencyInstallMode.REUSE_IF_VALID);
    }

    /** 在显式锁定文件变更策略下执行依赖项配置。 */
    default DependencyInstallResult ensureInstalled(Path projectDirectory,
                                                    String taskId,
                                                    DependencyInstallMode mode) {
        return ensureInstalled(projectDirectory);
    }

    /** 取消指定项目当前正在执行的依赖安装。 */
    boolean cancel(Path projectDirectory);
}
