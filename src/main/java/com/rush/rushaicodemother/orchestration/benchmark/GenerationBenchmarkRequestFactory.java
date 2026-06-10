package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class GenerationBenchmarkRequestFactory {

    private static final long BENCHMARK_USER_ID = -9000L;
    private final AtomicLong appIdSequence = new AtomicLong(-100_000L);

    public GenerationTaskRequest create(GenerationBenchmarkTask task) {
        App app = App.builder()
                .id(appIdSequence.getAndDecrement())
                .appName("benchmark-" + safeTaskId(task))
                .initPrompt(task == null ? "" : task.prompt())
                .codeGenType(task == null ? "" : task.codeGenType())
                .userId(BENCHMARK_USER_ID)
                .build();
        User user = new User();
        user.setId(BENCHMARK_USER_ID);
        user.setUserName("generation-benchmark");
        user.setUserAccount("generation-benchmark");
        return new GenerationTaskRequest(app, task == null ? "" : task.prompt(), user);
    }

    private String safeTaskId(GenerationBenchmarkTask task) {
        if (task == null || task.id() == null || task.id().isBlank()) {
            return "unknown";
        }
        return task.id();
    }
}
