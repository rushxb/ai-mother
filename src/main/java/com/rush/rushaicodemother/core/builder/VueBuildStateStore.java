package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * 原子读写 Vue 构建状态，并在首次成功写入新状态后移除旧版分散 stamp 文件。
 */
@Slf4j
@Component
public class VueBuildStateStore {

    static final String STATE_FILE_NAME = ".ai-code-build-state.json";
    private static final int STATE_VERSION = 1;
    private static final Set<String> LEGACY_STATE_FILES = Set.of(
            ".ai-code-install.stamp",
            ".ai-code-critical.stamp",
            ".ai-code-presentation.stamp"
    );

    synchronized VueBuildState read(Path projectRoot) {
        Path stateFile = projectRoot.resolve(STATE_FILE_NAME);
        if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(stateFile)) {
            return VueBuildState.empty();
        }
        try {
            JSONObject json = JSONUtil.parseObj(Files.readString(stateFile, StandardCharsets.UTF_8));
            if (json.getInt("version", -1) != STATE_VERSION) {
                log.debug("忽略不兼容的 Vue 构建状态文件: {}", stateFile);
                return VueBuildState.empty();
            }
            return new VueBuildState(
                    json.getStr("dependencyFingerprint", ""),
                    json.getStr("criticalFingerprint", ""),
                    json.getStr("presentationFingerprint", "")
            );
        } catch (Exception exception) {
            log.debug("读取 Vue 构建状态失败，将按未缓存处理: {}, {}", stateFile, LogExceptionSanitizer.sanitizeMessage(exception));
            return VueBuildState.empty();
        }
    }

    synchronized void recordDependencyInstalled(Path projectRoot, String dependencyFingerprint) throws IOException {
        VueBuildState currentState = read(projectRoot);
        write(projectRoot, currentState.withDependencyFingerprint(dependencyFingerprint));
    }

    synchronized void persist(Path projectRoot, VueProjectSnapshot snapshot) throws IOException {
        write(projectRoot, VueBuildState.fromSnapshot(snapshot));
    }

    static boolean isManagedStateFile(String normalizedRelativePath) {
        return STATE_FILE_NAME.equals(normalizedRelativePath) || LEGACY_STATE_FILES.contains(normalizedRelativePath);
    }

    private void write(Path projectRoot, VueBuildState state) throws IOException {
        Path stateFile = projectRoot.resolve(STATE_FILE_NAME);
        Path temporaryFile = Files.createTempFile(projectRoot, ".ai-code-build-state-", ".tmp");
        try {
            JSONObject json = new JSONObject();
            json.set("version", STATE_VERSION);
            json.set("dependencyFingerprint", state.dependencyFingerprint());
            json.set("criticalFingerprint", state.criticalFingerprint());
            json.set("presentationFingerprint", state.presentationFingerprint());
            Files.writeString(
                    temporaryFile,
                    json.toStringPretty(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            moveAtomically(temporaryFile, stateFile);
            removeLegacyStateFiles(projectRoot);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void removeLegacyStateFiles(Path projectRoot) {
        for (String legacyFileName : LEGACY_STATE_FILES) {
            try {
                Files.deleteIfExists(projectRoot.resolve(legacyFileName));
            } catch (IOException exception) {
                log.debug("清理旧版 Vue 构建状态文件失败: {}, {}", legacyFileName, LogExceptionSanitizer.sanitizeMessage(exception));
            }
        }
    }
}
