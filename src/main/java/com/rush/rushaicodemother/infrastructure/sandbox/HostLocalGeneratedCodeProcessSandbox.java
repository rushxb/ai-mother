package com.rush.rushaicodemother.infrastructure.sandbox;

import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** 仅用于开发的后端，保留现有的主机进程行为。 */
@Component
@ConditionalOnProperty(
        name = "app.generated-code-sandbox.mode",
        havingValue = "host-local",
        matchIfMissing = true
)
public class HostLocalGeneratedCodeProcessSandbox implements GeneratedCodeProcessSandbox {

    /**
 * 准备后续流程所需的主机{@code Local}{@code Generated}代码进程{@code Sandbox}。
 *
 * @param request 请求参数
 * @param normalizedWorkingDirectory {@code normalizedWorkingDirectory} 对应的调用参数
 * @return 主机{@code Local}{@code Generated}代码进程{@code Sandbox}
 */
    @Override
    public SandboxProcessPlan prepare(ManagedProcessRequest request, Path normalizedWorkingDirectory) {
        return new SandboxProcessPlan(
                "host-local",
                normalizedWorkingDirectory,
                request.command(),
                request.environment(),
                request.environmentVariablesToRemove(),
                ""
        );
    }
}
