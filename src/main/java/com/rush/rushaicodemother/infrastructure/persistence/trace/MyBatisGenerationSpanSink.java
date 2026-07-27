package com.rush.rushaicodemother.infrastructure.persistence.trace;

import com.rush.rushaicodemother.mapper.GenerationTaskSpanMapper;
import com.rush.rushaicodemother.model.entity.GenerationTaskSpan;
import com.rush.rushaicodemother.monitor.span.GenerationSpanObservation;
import com.rush.rushaicodemother.monitor.span.GenerationSpanSink;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/** MyBatis 适配器可持久存储完整的生成跨度。 */
@Repository
public class MyBatisGenerationSpanSink implements GenerationSpanSink {

    private final GenerationTaskSpanMapper mapper;
    private final ZoneId databaseZone;

    @Autowired
    public MyBatisGenerationSpanSink(GenerationTaskSpanMapper mapper) {
        this(mapper, ZoneId.systemDefault());
    }

    MyBatisGenerationSpanSink(GenerationTaskSpanMapper mapper, ZoneId databaseZone) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.databaseZone = Objects.requireNonNull(databaseZone, "databaseZone");
    }

    @Override
    public void record(GenerationSpanObservation observation) {
        Objects.requireNonNull(observation, "observation");
        GenerationTaskSpan entity = GenerationTaskSpan.builder()
                .spanId(observation.spanId())
                .taskId(observation.taskId())
                .stage(observation.stage())
                .category(observation.category().name().toLowerCase(java.util.Locale.ROOT))
                .status(observation.status())
                .startedAt(toLocalDateTime(observation.startedAt()))
                .endedAt(toLocalDateTime(observation.endedAt()))
                .durationMs(observation.durationMs())
                .detail(observation.detail())
                .createTime(toLocalDateTime(observation.endedAt()))
                .isDelete(0)
                .build();
        mapper.insertSpan(entity);
    }

    private LocalDateTime toLocalDateTime(java.time.Instant instant) {
        return LocalDateTime.ofInstant(instant, databaseZone);
    }
}
