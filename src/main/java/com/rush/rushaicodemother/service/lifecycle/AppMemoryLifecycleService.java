package com.rush.rushaicodemother.service.lifecycle;

/** 删除应用程序时删除派生的 AI 内存。 */
public interface AppMemoryLifecycleService {

    void scheduleApplicationMemoryDeletion(Long tenantId, Long appId, Long requestedByUserId);
}
