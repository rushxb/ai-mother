package com.rush.rushaicodemother.orchestration.readonly;

/** 只读分析模型端口；实现方只能接收上下文，不能获得工具或文件系统句柄。 */
@FunctionalInterface
public interface ReadOnlyAnalysisModel {

    ReadOnlyAnalysisResult analyze(String taskId, ReadOnlyAnalysisRequest request);
}
