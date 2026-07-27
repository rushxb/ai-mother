package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.config.GenerationBenchmarkWorkerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 以同目录原子替换方式输出 Worker 结果。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.generation-benchmark.worker",
        name = "enabled",
        havingValue = "true")
public class GenerationBenchmarkWorkerResultWriter {

    private final GenerationBenchmarkWorkerProperties properties;
    private final ObjectMapper objectMapper;

    public void prepare() {
        Path target = target();
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(
                    target.getParent(), ".benchmark-worker-probe-", ".tmp");
        } catch (IOException failure) {
            throw new IllegalStateException("Benchmark Worker 结果目录不可写", failure);
        } finally {
            deleteTemporary(temporary);
        }
    }

    public void write(GenerationBenchmarkWorkerResult result) {
        if (result == null) {
            throw new IllegalArgumentException("Benchmark Worker 结果不能为空");
        }
        Path target = target();
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(
                    target.getParent(), ".benchmark-worker-result-", ".tmp");
            Files.write(temporary, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(result));
            moveAtomically(temporary, target);
            temporary = null;
        } catch (IOException failure) {
            throw new IllegalStateException("Benchmark Worker 结果写入失败", failure);
        } finally {
            deleteTemporary(temporary);
        }
    }

    private Path target() {
        try {
            Path target = Path.of(properties.getOutputFile()).toAbsolutePath().normalize();
            if (target.getParent() == null || target.getFileName() == null) {
                throw new IllegalStateException("Benchmark Worker 结果路径无效");
            }
            return target;
        } catch (RuntimeException invalid) {
            if (invalid instanceof IllegalStateException stateFailure) {
                throw stateFailure;
            }
            throw new IllegalStateException("Benchmark Worker 结果路径无效", invalid);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("结果文件系统不支持原子替换", unsupported);
        }
    }

    private void deleteTemporary(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 主异常比临时文件清理失败更重要，残留文件使用隐藏前缀且不会被读取为结果。
        }
    }
}
