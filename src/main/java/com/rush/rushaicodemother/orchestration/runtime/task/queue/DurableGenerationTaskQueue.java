package com.rush.rushaicodemother.orchestration.runtime.task.queue;

import java.util.Collection;
import java.util.List;

/** Durable queue port. MySQL remains the task source of truth; the queue provides worker delivery. */
public interface DurableGenerationTaskQueue {

    void enqueue(String taskId);

    List<GenerationTaskQueueDelivery> readNew();

    List<GenerationTaskQueueDelivery> reclaimExpired();

    void heartbeat(Collection<GenerationTaskQueueDelivery> deliveries);

    void acknowledge(GenerationTaskQueueDelivery delivery);

    void deadLetter(GenerationTaskQueueDelivery delivery, String reason);
}
