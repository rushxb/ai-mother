package com.rush.rushaicodemother.service.lifecycle;

/** Removes derived AI memories when an application is deleted. */
public interface AppMemoryLifecycleService {

    void scheduleApplicationMemoryDeletion(Long tenantId, Long appId, Long requestedByUserId);
}
