package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.orchestration.verification.runtime.ProjectRuntimeValidationResult;

/** 工程类型的真实运行时验证能力。 */
public interface GenerationProjectRuntimeValidationAdapter extends GenerationProjectTypeAdapter {

    /** 执行进程、端口、HTTP 或浏览器运行时验证。 */
    ProjectRuntimeValidationResult validateRuntime(
            GenerationProjectRuntimeValidationRequest request
    );
}
