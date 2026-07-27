package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Go 工具链可执行文件配置。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.go-toolchain")
public class GoToolchainProperties {

    /** Go 可执行文件名或绝对路径。 */
    @NotBlank
    private String goExecutable = "go";

    @AssertTrue(message = "Go 工具链可执行文件配置无效")
    public boolean isExecutableConfigurationSafe() {
        return goExecutable != null
                && !goExecutable.isBlank()
                && goExecutable.indexOf('\0') < 0;
    }
}
