package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.mapper.GenerationTaskSpanMapper;
import com.rush.rushaicodemother.model.entity.GenerationTaskSpan;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationSampleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** MyBatis 适配器为路由级历史配置文件加载有界持续时间样本。 */
@Repository
@RequiredArgsConstructor
public class MyBatisGenerationDurationSampleRepository implements GenerationDurationSampleRepository {

    private final GenerationTaskRuntimeMapper taskMapper;
    private final GenerationTaskSpanMapper spanMapper;

    /**
 * 加载{@code Recent}{@code Successful}{@code Samples}。
 *
 * @param route 代理路由
 * @param taskSampleLimit {@code taskSampleLimit} 对应的调用参数
 * @param spanSampleLimit {@code spanSampleLimit} 对应的调用参数
 * @return {@code Recent}{@code Successful}{@code Samples}
 */
    @Override
    public GenerationDurationSamples loadRecentSuccessfulSamples(String route,
                                                                 int taskSampleLimit,
                                                                 int spanSampleLimit) {
        requireRoute(route);
        requireLimit(taskSampleLimit, 2_000, "taskSampleLimit");
        requireLimit(spanSampleLimit, 20_000, "spanSampleLimit");
        List<Long> taskDurations = taskMapper.selectRecentSuccessfulDurationsByRoute(route, taskSampleLimit);
        List<GenerationTaskSpan> spans = spanMapper.selectRecentSuccessfulByRoute(route, spanSampleLimit);
        List<GenerationStageDurationSample> stageDurations = spans == null ? List.of() : spans.stream()
                .filter(java.util.Objects::nonNull)
                .map(span -> new GenerationStageDurationSample(
                        span.getStage(), span.getCategory(), safeDuration(span.getDurationMs())))
                .toList();
        return new GenerationDurationSamples(
                taskDurations == null ? List.of() : taskDurations,
                stageDurations
        );
    }

    private long safeDuration(Long durationMs) {
        return durationMs == null ? -1L : durationMs;
    }

    private void requireRoute(String route) {
        if (route == null || !route.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("route format is invalid");
        }
    }

    private void requireLimit(int limit, int maximum, String name) {
        if (limit <= 0 || limit > maximum) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
        }
    }
}
