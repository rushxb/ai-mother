package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.config.AiToolWorkspaceProperties;
import com.rush.rushaicodemother.constant.AppConstant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;

/** Isolated generated-project workspace used by AI file tool tests. */
record ToolWorkspaceTestProject(
        long appId,
        Path root,
        ToolWorkspaceFileService fileService
) implements AutoCloseable {

    static ToolWorkspaceTestProject create(long appId) throws IOException {
        return create(appId, new AiToolWorkspaceProperties());
    }

    static ToolWorkspaceTestProject create(
            long appId,
            AiToolWorkspaceProperties properties
    ) throws IOException {
        Path root = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId)
                .toAbsolutePath()
                .normalize();
        deleteRecursively(root);
        Files.createDirectories(root);
        return new ToolWorkspaceTestProject(
                appId,
                root,
                ToolPathSupportTestFixture.workspaceForApp(appId, properties)
        );
    }

    @Override
    public void close() throws IOException {
        deleteRecursively(root);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root)) {
            Files.deleteIfExists(root);
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
