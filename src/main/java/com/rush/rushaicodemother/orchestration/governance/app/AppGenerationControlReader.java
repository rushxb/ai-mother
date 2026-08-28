package com.rush.rushaicodemother.orchestration.governance.app;

/** 应用生成控制的只读端口，供准入、执行计划和工具策略复用同一事实源。 */
@FunctionalInterface
public interface AppGenerationControlReader {

    AppGenerationControlPolicy get(Long appId);

    static AppGenerationControlReader defaultsOnly() {
        return AppGenerationControlPolicy::defaults;
    }
}
