package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.config.ArtifactLifecycleProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Windows robocopy 目录复制适配器。 */
@Slf4j
@Component
public class RobocopyDirectoryCopier {

    private static final int FIRST_FAILURE_EXIT_CODE = 8;

    private final ManagedProcessExecutor processExecutor;
    private final ArtifactLifecycleProperties properties;

    public RobocopyDirectoryCopier(
            ManagedProcessExecutor processExecutor,
            ArtifactLifecycleProperties properties
    ) {
        this.processExecutor = processExecutor;
        this.properties = properties;
    }

    public void copy(
            Path sourceRoot,
            Path targetRoot,
            List<String> excludedDirectories,
            List<String> excludedFiles
    ) throws IOException, InterruptedException {
        List<String> command = buildCommand(
                sourceRoot,
                targetRoot,
                excludedDirectories,
                excludedFiles
        );
        ManagedProcessResult result = processExecutor.execute(
                ManagedProcessRequest.builder()
                        .workingDirectory(sourceRoot)
                        .command(command)
                        .timeout(properties.getCopyTimeout())
                        .heartbeatInterval(properties.getHeartbeatInterval())
                        .outputDrainTimeout(properties.getOutputDrainTimeout())
                        .maxOutputLength(properties.getMaxOutputLength())
                        .redirectErrorStream(true)
                        .outputCharset(StandardCharsets.UTF_16LE)
                        .logCategory("artifact-copy")
                        .logContext(sourceRoot + " -> " + targetRoot)
                        .build()
        );
        if (result.status() == ManagedProcessResult.Status.INTERRUPTED) {
            throw new InterruptedException("robocopy 复制线程被中断");
        }
        if (!result.completed()) {
            throw new IOException("robocopy 复制未完成: " + safeDetail(result));
        }
        int exitCode = result.exitCode();
        if (exitCode >= FIRST_FAILURE_EXIT_CODE) {
            throw new IOException(
                    "robocopy 复制失败，exit code: " + exitCode + ", output: " + limitedOutput(result)
            );
        }
        if (exitCode > 0) {
            log.debug("robocopy 复制完成，exitCode={}, output={}", exitCode, limitedOutput(result));
        }
    }

    List<String> buildCommand(
            Path sourceRoot,
            Path targetRoot,
            List<String> excludedDirectories,
            List<String> excludedFiles
    ) {
        List<String> command = new ArrayList<>(List.of(
                "robocopy",
                sourceRoot.toString(),
                targetRoot.toString(),
                "/E",
                "/COPY:DAT",
                "/DCOPY:DAT",
                "/SL",
                "/XJ",
                "/R:1",
                "/W:1",
                "/NFL",
                "/NDL",
                "/NJH",
                "/NJS",
                "/NP",
                "/UNICODE"
        ));
        if (excludedDirectories != null && !excludedDirectories.isEmpty()) {
            command.add("/XD");
            command.addAll(excludedDirectories);
        }
        if (excludedFiles != null && !excludedFiles.isEmpty()) {
            command.add("/XF");
            command.addAll(excludedFiles);
        }
        return List.copyOf(command);
    }

    private String safeDetail(ManagedProcessResult result) {
        if (result.errorDetail() != null && !result.errorDetail().isBlank()) {
            return result.errorDetail();
        }
        return result.status().name();
    }

    private String limitedOutput(ManagedProcessResult result) {
        String output = result.combinedOutput()
                .replace('\r', ' ')
                .replace('\n', ' ')
                .strip();
        int maxLength = Math.min(1_000, properties.getMaxOutputLength());
        return output.length() <= maxLength
                ? output
                : output.substring(output.length() - maxLength);
    }
}
