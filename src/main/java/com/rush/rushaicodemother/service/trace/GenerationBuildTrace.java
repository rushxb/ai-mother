package com.rush.rushaicodemother.service.trace;

import java.time.LocalDateTime;

/** 提供给生成记忆模块的只读构建 trace。 */
public record GenerationBuildTrace(
        String taskId,
        String stage,
        boolean success,
        String summary,
        String report,
        LocalDateTime createTime
) {
}
