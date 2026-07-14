package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.config.AiToolWorkspaceProperties;
import com.rush.rushaicodemother.config.PatchExecutionProperties;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceFileService;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;

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
        return new ToolPathSupport(contextService);
    }

    static ToolPathSupport from(GenerationToolExecutionContextService contextService) {
        return new ToolPathSupport(contextService);
    }

    static ToolWorkspaceFileService workspaceForApp(long appId) {
        return workspaceForApp(appId, new AiToolWorkspaceProperties());
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
