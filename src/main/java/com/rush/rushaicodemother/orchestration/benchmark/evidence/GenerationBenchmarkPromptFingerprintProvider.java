package com.rush.rushaicodemother.orchestration.benchmark.evidence;

/** 区分当前进程实际 Prompt 与数据库目标 Prompt 的身份来源。 */
public interface GenerationBenchmarkPromptFingerprintProvider {

    String currentRuntimeFingerprint();

    String currentDurableFingerprint();
}
