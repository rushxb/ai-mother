package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Node.js 工具链可执行文件配置。
 *
 * <p>所有 Node、pnpm 子进程统一使用此配置，避免不同模块解析到不同版本。</p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.node-toolchain")
public class NodeToolchainProperties {

    /** Node.js 可执行文件名或绝对路径。 */
    @NotBlank
    private String nodeExecutable = "node";

    /** pnpm 可执行文件名或绝对路径。 */
    @NotBlank
    private String pnpmExecutable = "pnpm";

    @AssertTrue(message = "Node.js 工具链可执行文件不能包含空字符")
    public boolean isExecutableConfigurationSafe() {
        return isSafeExecutable(nodeExecutable) && isSafeExecutable(pnpmExecutable);
    }

    private boolean isSafeExecutable(String executable) {
        return executable != null
                && !executable.isBlank()
                && executable.indexOf('\0') < 0;
    }
}
