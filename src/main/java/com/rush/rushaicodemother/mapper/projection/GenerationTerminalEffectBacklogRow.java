package com.rush.rushaicodemother.mapper.projection;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 终态副作用 outbox 积压聚合的关系投影。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerationTerminalEffectBacklogRow {
    private Long pending;
    private Long retrying;
    private Long leased;
    private Long deadLetter;
    private LocalDateTime oldestPendingAt;
}
