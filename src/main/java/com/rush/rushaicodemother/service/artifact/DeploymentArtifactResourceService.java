package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.SecurePathResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 解析隔离部署工件根内的公共部署资源。
 *
 * <p>HTTP层仅提供部署密钥和相对资源路径。文件系统根
 * 选择、部署密钥验证、遍历保护和符号链接遏制保留
 * 在此模块后面。</p>
 */
@Service
public class DeploymentArtifactResourceService {

    private final Path deployRoot;
    private final SecurePathResolver securePathResolver;
    private final DeploymentKeyPolicy deploymentKeyPolicy;

    /**
 * 创建部署制品资源服务实例并完成必要的依赖和初始状态设置。
 *
 * @param storageProperties 存储属性
 * @param deploymentKeyPolicy 部署键策略
 * @param securePathResolver 安全路径解析器
 */
    public DeploymentArtifactResourceService(
            CodeStorageProperties storageProperties,
            DeploymentKeyPolicy deploymentKeyPolicy,
            SecurePathResolver securePathResolver
    ) {
        Objects.requireNonNull(storageProperties, "storageProperties must not be null");
        this.deployRoot = storageProperties.deployRoot();
        this.securePathResolver = Objects.requireNonNull(
                securePathResolver,
                "securePathResolver must not be null"
        );
        this.deploymentKeyPolicy = Objects.requireNonNull(
                deploymentKeyPolicy,
                "deploymentKeyPolicy must not be null"
        );
    }

    /** 从一个已提交的部署目录解析现有常规文件。 */
    public Path resolve(String deployKey, String relativePath) throws IOException {
        deploymentKeyPolicy.requireValid(deployKey);
        return securePathResolver.resolveRegularFile(deployRoot, deployKey, relativePath);
    }
}
