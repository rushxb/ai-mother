package com.rush.rushaicodemother.orchestration.benchmark.evidence;

/** 提供当前制品和部署配置对应的发布来源清单。 */
public interface GenerationReleaseProvenanceProvider {

    GenerationReleaseProvenanceManifest current();
}
