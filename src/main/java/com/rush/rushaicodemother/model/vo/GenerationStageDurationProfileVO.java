package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationStageDurationProfile;

/** 一份历史生成操作持续时间配置文件的管理员视图。 */
public record GenerationStageDurationProfileVO(
        String stage,
        String category,
        int sampleSize,
        long p50DurationMs,
        long p90DurationMs,
        long maxDurationMs
) {
    /**
 * 根据输入数据创建当前对象。
 *
 * @param profile 配置档
 * @return 生成阶段时长配置档视图对象
 */
    public static GenerationStageDurationProfileVO from(GenerationStageDurationProfile profile) {
        return new GenerationStageDurationProfileVO(
                profile.stage(), profile.category(), profile.sampleSize(),
                profile.p50DurationMs(), profile.p90DurationMs(), profile.maxDurationMs());
    }
}
