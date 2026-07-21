package com.rush.rushaicodemother.orchestration.runtime.task.queue;

/** One Redis Streams delivery with the metadata required for ACK, reclaim and DLQ. */
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
