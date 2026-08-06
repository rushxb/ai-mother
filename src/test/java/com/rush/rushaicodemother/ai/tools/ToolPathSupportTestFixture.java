package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.config.AiToolWorkspaceProperties;
import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.config.PatchExecutionProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceFileService;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;

/**
 * Creates real path resolvers for tool tests without bootstrapping the Spring context.
 */
final class ToolPathSupportTestFixture {

    private ToolPathSupportTestFixture() {
    }

    static ToolPathSupport forApp(long appId) {
        return forApp(appId, CodeGenTypeEnum.VUE_PROJECT);
    }

    static ToolPathSupport forApp(long appId, CodeGenTypeEnum codeGenType) {
        return forApp(appId, codeGenType, new CodeStorageProperties());
    }

    static ToolPathSupport forApp(
            long appId,
            CodeGenTypeEnum codeGenType,
            CodeStorageProperties storageProperties
    ) {
        GenerationToolExecutionContextService contextService = new GenerationToolExecutionContextService();
        contextService.bindChangePlan(
                appId,
                "test-task-" + appId,
                "full_generation",
                codeGenType,
                null,
                true,
                "test"
        );
        return from(contextService, storageProperties);
    }

    /**
     * 直接绑定调用方提供的工作区根，绕过解析层的目录安全校验。
     *
     * <p>用于验证工具边界自身的兜底拒绝：受管执行会把隔离工作区直接注入上下文，
     * 此时 {@code ToolPathSupport} 是唯一的符号链接防线。</p>
     */
    static ToolPathSupport forSuppliedWorkspace(
            long appId,
            CodeGenTypeEnum codeGenType,
            java.nio.file.Path rootPath,
            CodeStorageProperties storageProperties
    ) {
        GenerationToolExecutionContextService contextService = new GenerationToolExecutionContextService();
        contextService.bindChangePlan(
                appId,
                "test-task-" + appId,
                "full_generation",
                codeGenType,
                null,
                true,
                "test"
        );
        contextService.bindWorkspace(appId, "test-task-" + appId, new GenerationWorkspace(
                appId,
                codeGenType,
                rootPath,
                rootPath,
                true,
                rootPath,
                rootPath,
                java.util.Set.of(),
                java.util.Set.of("vue", "ts", "js", "json")
        ));
        return from(contextService, storageProperties);
    }

    static ToolPathSupport from(GenerationToolExecutionContextService contextService) {
        return from(contextService, new CodeStorageProperties());
    }

    static ToolPathSupport from(
            GenerationToolExecutionContextService contextService,
            CodeStorageProperties storageProperties
    ) {
        return new ToolPathSupport(
                contextService,
                new GenerationWorkspaceService(storageProperties)
        );
    }

    static ToolWorkspaceFileService workspaceForApp(long appId) {
        return workspaceForApp(appId, new AiToolWorkspaceProperties());
    }

    static ToolWorkspaceFileService workspaceForApp(long appId, CodeGenTypeEnum codeGenType) {
        return workspaceFrom(forApp(appId, codeGenType), new AiToolWorkspaceProperties());
    }

    static ToolWorkspaceFileService workspaceForApp(
            long appId,
            AiToolWorkspaceProperties workspaceProperties
    ) {
        return workspaceFrom(forApp(appId), workspaceProperties);
    }

    static ToolWorkspaceFileService workspaceFrom(
            ToolPathSupport pathSupport,
            AiToolWorkspaceProperties workspaceProperties
    ) {
        return new ToolWorkspaceFileService(
                pathSupport,
                new PatchWorkspaceFileService(new PatchExecutionProperties()),
                workspaceProperties
        );
    }
}
