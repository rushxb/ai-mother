package com.rush.rushaicodemother.service.lifecycle;

/** 应用删除生命周期模块。 */
public interface AppDeletionService {

    /**
     * 在应用操作锁内重新读取最新状态，并删除应用及其关联数据和本地产物。
     *
     * @param appId 待删除应用 ID
     */
    void delete(Long appId);
}
