package com.rush.rushaicodemother.orchestration.finalization;

/** 已发布结果的数据库终态暂未提交，必须保留原成功意图并交由恢复扫描重试。 */
public class GenerationFinalizationDeferredException extends RuntimeException {

    public GenerationFinalizationDeferredException(String message, Throwable cause) {
        super(message, cause);
    }
}
