package com.rush.rushaicodemother.ai.model.message;

import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Vue 项目构建诊断消息
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class BuildResultMessage extends StreamMessage {

    private Boolean success;

    private String stage;

    private String projectPath;

    private String summary;

    private String report;

    public BuildResultMessage(VueProjectBuilder.BuildResult buildResult) {
        super(StreamMessageTypeEnum.BUILD_RESULT.getValue());
        this.success = buildResult.success();
        this.stage = buildResult.stage();
        this.projectPath = buildResult.projectPath();
        this.summary = buildResult.summary();
        this.report = buildResult.toDiagnosticReport();
    }
}
