package com.rush.rushaicodemother.infrastructure.process;

import com.rush.rushaicodemother.config.GoToolchainProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/** 统一解析 Go 可执行文件，兼容 Windows 的直接进程启动规则。 */
@Component
public class GoToolchain {

    private final String goExecutable;

    @Autowired
    public GoToolchain(GoToolchainProperties properties) {
        this(properties, isWindowsOperatingSystem());
    }

    GoToolchain(GoToolchainProperties properties, boolean windows) {
        Objects.requireNonNull(properties, "Go 工具链配置不能为空");
        String configured = properties.getGoExecutable();
        if (configured == null || configured.isBlank() || configured.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Go 工具链可执行文件配置无效");
        }
        String normalized = configured.strip();
        this.goExecutable = windows && "go".equalsIgnoreCase(normalized)
                ? "go.exe"
                : normalized;
    }

    public String goExecutable() {
        return goExecutable;
    }

    private static boolean isWindowsOperatingSystem() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("windows");
    }
}
