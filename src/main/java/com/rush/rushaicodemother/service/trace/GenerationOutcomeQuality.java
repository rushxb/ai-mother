package com.rush.rushaicodemother.service.trace;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 单次生成任务的结果质量证据（L3 情景记录）。
 *
 * <p>每个字段都可为空，{@code null} 语义是「未采集」而不是「值为零」。写入时折叠进既有终态
 * UPDATE 并以 {@code COALESCE} 保护，因此重试传 {@code null} 不会擦掉已采集的值。</p>
 *
 * <p>{@code thinkingMode} 依赖思考模式与执行计划联动，{@code reworkedAt} 依赖隐式验收信号，
 * {@code distilledAt} 依赖经验蒸馏；这三项目前恒为 {@code null}，属于已记录阻塞依赖的设计状态。</p>
 *
 * @param thinkingMode 实际使用的思考档位
 * @param changedFileCount 有效变更文件数
 * @param firstBuildPassed 是否免修复通过构建
 * @param repairRounds 实际修复轮次
 * @param firstPreviewMillis 提交到可预览耗时毫秒
 * @param failureCategory 失败分类，取值来自生成错误分类器
 * @param reworkedAt 交付后被追加改修的时间
 * @param distilledAt 经验已蒸馏时间
 */
public record GenerationOutcomeQuality(
        String thinkingMode,
        Integer changedFileCount,
        Boolean firstBuildPassed,
        Integer repairRounds,
        Long firstPreviewMillis,
        String failureCategory,
        LocalDateTime reworkedAt,
        LocalDateTime distilledAt
) {

    /** 思考档位列宽上限。 */
    private static final int MAX_THINKING_MODE_LENGTH = 16;

    /** 失败分类列宽上限。 */
    private static final int MAX_FAILURE_CATEGORY_LENGTH = 64;

    public GenerationOutcomeQuality {
        thinkingMode = normalizeToken(thinkingMode, MAX_THINKING_MODE_LENGTH, "思考档位");
        failureCategory = normalizeToken(failureCategory, MAX_FAILURE_CATEGORY_LENGTH, "失败分类");
        requireNonNegative(changedFileCount, "有效变更文件数");
        requireNonNegative(repairRounds, "修复轮次");
        requireNonNegative(firstPreviewMillis, "首预览耗时");
    }

    /** 全部字段未采集的空证据；用于尚未接入指标的调用方。 */
    public static GenerationOutcomeQuality empty() {
        return new GenerationOutcomeQuality(null, null, null, null, null, null, null, null);
    }

    /**
     * 构造成功终态的结果质量证据。
     *
     * @param changedFileCount 有效变更文件数
     * @param repairRounds 实际修复轮次
     * @param firstBuildPassed 是否免修复通过构建
     * @param firstPreviewMillis 提交到可预览耗时毫秒
     * @return 结果质量证据
     */
    public static GenerationOutcomeQuality ofSuccess(Integer changedFileCount,
                                                     Integer repairRounds,
                                                     Boolean firstBuildPassed,
                                                     Long firstPreviewMillis) {
        return new GenerationOutcomeQuality(
                null, changedFileCount, firstBuildPassed, repairRounds,
                firstPreviewMillis, null, null, null);
    }

    /**
     * 构造失败终态的结果质量证据。
     *
     * @param failureCategory 失败分类
     * @param changedFileCount 有效变更文件数
     * @param repairRounds 实际修复轮次
     * @param firstPreviewMillis 提交到可预览耗时毫秒
     * @return 结果质量证据
     */
    public static GenerationOutcomeQuality ofFailure(String failureCategory,
                                                     Integer changedFileCount,
                                                     Integer repairRounds,
                                                     Long firstPreviewMillis) {
        return new GenerationOutcomeQuality(
                null, changedFileCount, null, repairRounds,
                firstPreviewMillis, failureCategory, null, null);
    }

    /** 判断是否完全没有采集到任何字段，便于调用方跳过无意义的写入参数装配。 */
    public boolean isEmpty() {
        return thinkingMode == null
                && changedFileCount == null
                && firstBuildPassed == null
                && repairRounds == null
                && firstPreviewMillis == null
                && failureCategory == null
                && reworkedAt == null
                && distilledAt == null;
    }

    /** 供持久化层写入 tinyint 列；未采集时返回 {@code null}。 */
    public Integer firstBuildPassedValue() {
        return firstBuildPassed == null ? null : (firstBuildPassed ? 1 : 0);
    }

    /** 归一化为小写标识并裁剪到列宽；空白视为未采集。 */
    private static String normalizeToken(String value, int maxLength, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + "超出长度上限: " + maxLength);
        }
        return normalized;
    }

    private static void requireNonNegative(Number value, String name) {
        if (value != null && value.longValue() < 0) {
            throw new IllegalArgumentException(name + "不能为负数");
        }
    }
}
