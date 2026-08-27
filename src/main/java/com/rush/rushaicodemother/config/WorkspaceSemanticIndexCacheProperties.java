package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 工作区语义索引进程内快照的容量、权重与生命周期预算。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.workspace-semantic-index.cache")
public class WorkspaceSemanticIndexCacheProperties {

    @Min(1)
    @Max(4096)
    private int maximumWorkspaces = 256;

    @Min(1_000)
    private long maximumIndexedFiles = 50_000;

    @Min(16)
    @Max(4096)
    private int lockStripes = 256;

    @NotNull
    private Duration expireAfterAccess = Duration.ofMinutes(30);
}
