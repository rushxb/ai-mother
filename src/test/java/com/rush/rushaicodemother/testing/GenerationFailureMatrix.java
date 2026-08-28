package com.rush.rushaicodemother.testing;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 生成链路必做故障矩阵的 JUnit 标签。 */
public final class GenerationFailureMatrix {

    public static final String TAG = "generation-failure-matrix";

    private static final String LEASE_COORDINATOR_TEST =
            "com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskLeaseCoordinatorTest";

    private static final List<GenerationFailureSample> SAMPLES = validate(List.of(
            new GenerationFailureSample(
                    "cancellation_queued_activation_fenced",
                    GenerationFailureScenario.CANCELLATION,
                    LEASE_COORDINATOR_TEST,
                    "cancelledQueuedTaskMustCancelLocalContextInsteadOfStartingWork",
                    Set.of("taskId", "executionEpoch", "cancellationReason"),
                    Set.of("activation_rejected", "local_context_cancelled")
            ),
            new GenerationFailureSample(
                    "cancellation_heartbeat_propagated",
                    GenerationFailureScenario.CANCELLATION,
                    LEASE_COORDINATOR_TEST,
                    "heartbeatMustPropagateCancellationAndDropLostLease",
                    Set.of("taskId", "executionEpoch", "cancellationReason"),
                    Set.of("operator_reason_propagated", "lost_lease_removed")
            )
    ));

    private GenerationFailureMatrix() {
    }

    /** 返回指定类别的不可变故障样本清单。 */
    public static List<GenerationFailureSample> samplesFor(GenerationFailureScenario scenario) {
        if (scenario == null) {
            return List.of();
        }
        return SAMPLES.stream()
                .filter(sample -> sample.scenario() == scenario)
                .toList();
    }

    private static List<GenerationFailureSample> validate(List<GenerationFailureSample> samples) {
        Set<String> ids = new HashSet<>();
        Set<String> methodBindings = new HashSet<>();
        for (GenerationFailureSample sample : samples) {
            if (!ids.add(sample.id())) {
                throw new IllegalStateException("故障样本标识不能重复: " + sample.id());
            }
            String binding = sample.testClassName() + "#" + sample.testMethodName();
            if (!methodBindings.add(binding)) {
                throw new IllegalStateException("故障样本测试方法不能重复绑定: " + binding);
            }
        }
        return List.copyOf(samples);
    }
}
