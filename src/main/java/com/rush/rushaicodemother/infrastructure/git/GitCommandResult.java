package com.rush.rushaicodemother.infrastructure.git;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;

/** Git 命令的结构化执行结果。 */
public record GitCommandResult(
        ManagedProcessResult.Status status,
        Integer exitCode,
        String stdout,
        String stderr,
        String errorDetail
) {

    private static final int MAX_ERROR_SUMMARY_LENGTH = 240;

    /**
 * 返回成功。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean success() {
        return status == ManagedProcessResult.Status.COMPLETED
                && Integer.valueOf(0).equals(exitCode);
    }

    public boolean commandCompleted() {
        return status == ManagedProcessResult.Status.COMPLETED;
    }

    public boolean interrupted() {
        return status == ManagedProcessResult.Status.INTERRUPTED;
    }

    /**
 * 返回错误汇总。
 *
 * @return 处理后的{@code Git}命令结果文本
 */
    public String errorSummary() {
        String detail = firstNonBlank(stderr, errorDetail, stdout, "unknown");
        String normalized = detail
                .replace("\0", "")
                .replace("\r", " ")
                .replace("\n", " ")
                .trim();
        return StrUtil.sub(normalized, 0, Math.min(normalized.length(), MAX_ERROR_SUMMARY_LENGTH));
    }

    /** 返回首次{@code Non}{@code Blank}。 */
    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (StrUtil.isNotBlank(candidate)) {
                return candidate;
            }
        }
        return "unknown";
    }
}
