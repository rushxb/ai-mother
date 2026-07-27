package com.rush.rushaicodemother.infrastructure.persistence.trace;

import com.rush.rushaicodemother.mapper.GenerationTaskSpanMapper;
import com.rush.rushaicodemother.model.entity.GenerationTaskSpan;
import com.rush.rushaicodemother.monitor.span.GenerationSpanQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** MyBatis 支持的查询适配器，用于持久生成关键路径跨度。 */
@Service
public class MyBatisGenerationSpanQueryService implements GenerationSpanQueryService {

    private static final Pattern TASK_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final GenerationTaskSpanMapper mapper;
    private final ZoneId databaseZone;

    @Autowired
    public MyBatisGenerationSpanQueryService(GenerationTaskSpanMapper mapper) {
        this(mapper, ZoneId.systemDefault());
    }

    MyBatisGenerationSpanQueryService(GenerationTaskSpanMapper mapper, ZoneId databaseZone) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.databaseZone = Objects.requireNonNull(databaseZone, "databaseZone");
    }

    @Override
    public List<StoredSpan> findByTaskId(String taskId, Integer limit) {
        if (taskId == null || !TASK_ID_PATTERN.matcher(taskId).matches()) {
            throw new IllegalArgumentException("taskId format is invalid");
        }
        int safeLimit = limit == null || limit <= 0
                ? DEFAULT_LIMIT
                : Math.min(limit, MAX_LIMIT);
        List<GenerationTaskSpan> rows = mapper.selectByTaskId(taskId, safeLimit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .map(this::toStoredSpan)
                .toList();
    }

    private StoredSpan toStoredSpan(GenerationTaskSpan entity) {
        if (entity == null || entity.getStartedAt() == null || entity.getEndedAt() == null) {
            throw new IllegalStateException("generation span row is incomplete");
        }
        return new StoredSpan(
                entity.getSpanId(),
                entity.getTaskId(),
                entity.getStage(),
                entity.getCategory(),
                entity.getStatus(),
                entity.getStartedAt().atZone(databaseZone).toInstant(),
                entity.getEndedAt().atZone(databaseZone).toInstant(),
                entity.getDurationMs() == null ? 0 : Math.max(0, entity.getDurationMs()),
                entity.getDetail() == null ? "" : entity.getDetail()
        );
    }
}
