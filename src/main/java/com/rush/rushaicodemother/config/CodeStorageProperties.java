package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.constant.AppConstant;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 生成代码、部署工件和生成快照存储根。
 *
 * <p>此配置是文件系统根的依赖注入边界。遗产
 * {@link AppConstant} 值仅保留为现有 JVM 的向后兼容默认值
 * {@code -Dcode.*}启动参数；业务模块必须使用此配置
 * 直接读取静态路径常量。</p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "code")
public class CodeStorageProperties {

    /** 包含生成的应用程序工作区的根目录。 */
    @NotNull
    private Path outputRootDir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR);

    /** 包含由部署密钥寻址的不可变部署视图的根目录。 */
    @NotNull
    private Path deployRootDir = Path.of(AppConstant.CODE_DEPLOY_ROOT_DIR);

    /** 包含应用程序范围的生成快照的根目录。 */
    @NotNull
    private Path snapshotRootDir = Path.of(AppConstant.CODE_SNAPSHOT_ROOT_DIR);

    /** 返回标准化的绝对生成工作空间根。 */
    public Path outputRoot() {
        return normalizeRequired(outputRootDir, "code.output-root-dir");
    }

    /** 返回规范化的绝对部署工件根。 */
    public Path deployRoot() {
        return normalizeRequired(deployRootDir, "code.deploy-root-dir");
    }

    /** 返回标准化的绝对生成快照根。 */
    public Path snapshotRoot() {
        return normalizeRequired(snapshotRootDir, "code.snapshot-root-dir");
    }

    /**
     * 生成的源、部署工件和快照必须成对隔离。重叠
     * 根将允许清理、部署或回滚操作跨存储职责。
     */
    @AssertTrue(message = "Code output, deployment, and snapshot roots must be isolated")
    public boolean isStorageLayoutIsolated() {
        if (!isConfigured(outputRootDir) || !isConfigured(deployRootDir) || !isConfigured(snapshotRootDir)) {
            return false;
        }
        Path[] roots = {outputRoot(), deployRoot(), snapshotRoot()};
        for (int left = 0; left < roots.length; left++) {
            for (int right = left + 1; right < roots.length; right++) {
                if (overlaps(roots[left], roots[right])) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean overlaps(Path left, Path right) {
        return left.equals(right) || left.startsWith(right) || right.startsWith(left);
    }

    private boolean isConfigured(Path configuredPath) {
        return configuredPath != null && !configuredPath.toString().isBlank();
    }

    private Path normalizeRequired(Path configuredPath, String propertyName) {
        Path requiredPath = Objects.requireNonNull(configuredPath, propertyName + " must not be null");
        if (requiredPath.toString().isBlank()) {
            throw new IllegalStateException(propertyName + " must not be blank");
        }
        return requiredPath.toAbsolutePath().normalize();
    }
}
