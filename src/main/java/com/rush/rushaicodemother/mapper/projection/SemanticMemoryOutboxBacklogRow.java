package com.rush.rushaicodemother.mapper.projection;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 用于发布有界发件箱积压指标的关系投影。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemanticMemoryOutboxBacklogRow {
    private Long pending;
    private Long retrying;
    private Long leased;
    private Long deadLetter;
    private LocalDateTime oldestPendingAt;
}
