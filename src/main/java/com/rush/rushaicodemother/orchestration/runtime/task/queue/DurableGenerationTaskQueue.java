package com.rush.rushaicodemother.orchestration.runtime.task.queue;

import java.util.Collection;
import java.util.List;

/** 耐用的队列端口。 MySQL 仍然是任务的真相来源；队列提供工作人员交付。 */
public interface DurableGenerationTaskQueue {

    void enqueue(String taskId);

    List<GenerationTaskQueueDelivery> readNew();

    List<GenerationTaskQueueDelivery> reclaimExpired();

    void heartbeat(Collection<GenerationTaskQueueDelivery> deliveries);

    void acknowledge(GenerationTaskQueueDelivery delivery);

    void deadLetter(GenerationTaskQueueDelivery delivery, String reason);
}
