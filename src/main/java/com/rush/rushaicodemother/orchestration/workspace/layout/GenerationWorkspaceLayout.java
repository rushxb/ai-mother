package com.rush.rushaicodemother.orchestration.workspace.layout;

import java.nio.file.Path;

/**
 * 工程工作区中的角色根目录。
 *
 * <p>{@code null} 表示该工程不具备对应角色，避免把后端工程根目录伪装成前端根目录，
 * 进而让预览、前端构建或编辑候选错误地接管后端项目。</p>
 */
public record GenerationWorkspaceLayout(
        Path frontendRootPath,
        Path backendRootPath
) {

    public GenerationWorkspaceLayout {
        if (frontendRootPath == null && backendRootPath == null) {
            throw new IllegalArgumentException("工作区至少需要一个工程角色根目录");
        }
        frontendRootPath = normalize(frontendRootPath);
        backendRootPath = normalize(backendRootPath);
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }
}
