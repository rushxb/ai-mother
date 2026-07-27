package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationProfile;

import java.time.Instant;
import java.util.List;

/** 管理员对路线级总时间和操作持续时间百分位进行诊断。 */
public record GenerationDurationProfileVO(
        String route,
        int taskSampleSize,
        long p50TotalDurationMs,
        long p90TotalDurationMs,
        long maxTotalDurationMs,
        List<GenerationStageDurationProfileVO> stages,
        Instant computedAt
) {
    public static GenerationDurationProfileVO from(GenerationDurationProfile profile) {
        return new GenerationDurationProfileVO(
                profile.route(), profile.taskSampleSize(), profile.p50TotalDurationMs(),
                profile.p90TotalDurationMs(), profile.maxTotalDurationMs(),
                profile.stages().stream().map(GenerationStageDurationProfileVO::from).toList(),
                profile.computedAt());
    }
}
