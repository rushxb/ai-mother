package com.rush.rushaicodemother.orchestration.runtime.task.queue;

/** 一个 Redis Streams 交付，包含 ACK、回收和 DLQ 所需的元数据。 */
public record GenerationTaskQueueDelivery(
        String messageId,
        String taskId,
        long deliveryCount
) {
    public GenerationTaskQueueDelivery {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId cannot be blank");
        }
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("taskId format is invalid");
        }
        if (deliveryCount <= 0) {
            throw new IllegalArgumentException("deliveryCount must be positive");
        }
    }
}
