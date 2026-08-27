package com.rush.rushaicodemother.infrastructure.filesystem;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 可持久化的工作区目录内容指纹。
 *
 * <p>文件数和总字节数用于快速拒绝明显不一致，SHA-256 用于绑定完整的
 * 目录结构与文件内容。指纹不包含绝对路径和时间戳，因此可跨工作树校验。</p>
 */
public record WorkspaceDirectoryFingerprint(int fileCount, long totalBytes, String contentSha256) {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public WorkspaceDirectoryFingerprint {
        if (fileCount < 0) {
            throw new IllegalArgumentException("fileCount must not be negative");
        }
        if (totalBytes < 0) {
            throw new IllegalArgumentException("totalBytes must not be negative");
        }
        contentSha256 = Objects.requireNonNull(contentSha256, "contentSha256 must not be null")
                .toLowerCase(Locale.ROOT);
        if (!SHA256_HEX.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException("contentSha256 must be a 64-character hexadecimal SHA-256");
        }
    }

    /** 从一次完成的目录复制结果创建指纹。 */
    public static WorkspaceDirectoryFingerprint from(
            WorkspaceFileSystemService.WorkspaceCopyResult copyResult
    ) {
        Objects.requireNonNull(copyResult, "copyResult must not be null");
        return new WorkspaceDirectoryFingerprint(
                copyResult.fileCount(),
                copyResult.totalBytes(),
                copyResult.contentSha256()
        );
    }
}
