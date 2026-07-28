package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/** 读取 Maven 写入制品的不可变 Git 构建元数据。 */
@Component
public class GenerationGitBuildMetadataProvider {

    private final Resource resource;

    public GenerationGitBuildMetadataProvider(
            @Value("classpath:git.properties") Resource resource
    ) {
        this.resource = resource;
    }

    /**
 * 返回当前。
 *
 * @return 生成{@code Git}构建元数据提供方
 */
    public BuildMetadata current() {
        if (!resource.exists()) {
            throw unavailable("发布制品缺少 Git 构建元数据");
        }
        Properties properties = new Properties();
        try (InputStream input = resource.getInputStream()) {
            properties.load(input);
        } catch (IOException failure) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "无法读取发布制品的 Git 构建元数据",
                    failure
            );
        }
        String commit = normalize(properties.getProperty("git.commit.id.full"));
        if (!GenerationReleaseProvenanceManifest.isFullGitCommit(commit)) {
            throw unavailable("发布制品缺少完整 Git 提交哈希");
        }
        String dirtyValue = normalize(properties.getProperty("git.dirty"));
        if (!dirtyValue.matches("true|false")) {
            throw unavailable("发布制品缺少可信的 dirty 标记");
        }
        return new BuildMetadata(commit, Boolean.parseBoolean(dirtyValue));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private BusinessException unavailable(String message) {
        return new BusinessException(ErrorCode.OPERATION_ERROR, message + "，禁止使用 Benchmark 发布证据");
    }

    public record BuildMetadata(String commit, boolean dirty) {
    }
}
