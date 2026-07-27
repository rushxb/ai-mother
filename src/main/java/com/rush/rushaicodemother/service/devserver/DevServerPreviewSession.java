package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionState;

/** 源自本地运行时和持久所有权的集群可见预览生命周期视图。 */
public record DevServerPreviewSession(
        Long appId,
        String nodeId,
        Integer port,
        DevServerSessionState state,
        boolean local,
        boolean available
) {

    public boolean running() {
        return available && state == DevServerSessionState.RUNNING;
    }

    public String status() {
        if (state == null) {
            return "stopped";
        }
        if (state == DevServerSessionState.RUNNING && !available) {
            return "unavailable";
        }
        return state.name().toLowerCase(java.util.Locale.ROOT);
    }
}
