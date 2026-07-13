package com.rush.rushaicodemother.service.workspace;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.vo.AppCodeFileContentVO;
import com.rush.rushaicodemother.model.vo.AppCodeFileTreeVO;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAppCodeWorkspaceServiceTest {

    private static final AtomicLong APP_ID_SEQUENCE = new AtomicLong(System.currentTimeMillis() * 1000);

    private final List<Path> workspaceRoots = new ArrayList<>();

    private VueProjectBuilder vueProjectBuilder;
    private LocalAppCodeWorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        vueProjectBuilder = mock(VueProjectBuilder.class);
        workspaceService = new LocalAppCodeWorkspaceService(
                new GenerationWorkspaceService(),
                vueProjectBuilder
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Path workspaceRoot : workspaceRoots) {
            deleteTreeWithoutFollowingLinks(workspaceRoot);
        }
    }

    @Test
    void shouldListDirectoriesBeforeFilesAndHideInternalDirectories() throws IOException {
        WorkspaceFixture fixture = createWorkspace(CodeGenTypeEnum.HTML);
        Files.createDirectories(fixture.root().resolve("src"));
        Files.createDirectories(fixture.root().resolve("assets"));
        Files.createDirectories(fixture.root().resolve(".GIT"));
        Files.createDirectories(fixture.root().resolve("NODE_MODULES"));
        Files.writeString(fixture.root().resolve("z.js"), "console.log('z');", StandardCharsets.UTF_8);
        Files.writeString(fixture.root().resolve("README.md"), "readme", StandardCharsets.UTF_8);
        Files.writeString(fixture.root().resolve(".app-code-orphan.tmp"), "temporary", StandardCharsets.UTF_8);

        List<AppCodeFileTreeVO> nodes = workspaceService.listFiles(fixture.app());

        assertEquals(List.of("assets", "src", "README.md", "z.js"),
                nodes.stream().map(AppCodeFileTreeVO::getName).toList());
        assertTrue(nodes.get(0).getDirectory());
        assertFalse(nodes.get(2).getDirectory());
    }

    @Test
    void shouldReadUtf8FileAndReturnNormalizedRelativePath() throws IOException {
        WorkspaceFixture fixture = createWorkspace(CodeGenTypeEnum.HTML);
        Path sourceFile = fixture.root().resolve("src").resolve("页面.html");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "你好，生产环境", StandardCharsets.UTF_8);

        AppCodeFileContentVO content = workspaceService.readFile(fixture.app(), "src\\页面.html");

        assertEquals("src/页面.html", content.getPath());
        assertEquals("页面.html", content.getName());
        assertEquals("你好，生产环境", content.getContent());
        assertEquals(Files.size(sourceFile), content.getSize());
        assertTrue(content.getEditable());
    }

    @Test
    void shouldRejectTraversalAbsoluteHiddenAndUnsupportedPaths() throws IOException {
        WorkspaceFixture fixture = createWorkspace(CodeGenTypeEnum.HTML);
        Files.writeString(fixture.root().resolve("index.html"), "ok", StandardCharsets.UTF_8);
        Files.createDirectories(fixture.root().resolve(".git"));
        Files.writeString(fixture.root().resolve(".git").resolve("config"), "secret", StandardCharsets.UTF_8);
        Files.write(fixture.root().resolve("archive.bin"), new byte[]{1, 2, 3});

        assertErrorCode(ErrorCode.NO_AUTH_ERROR,
                () -> workspaceService.readFile(fixture.app(), "../outside.html"));
        assertErrorCode(ErrorCode.NO_AUTH_ERROR,
                () -> workspaceService.readFile(fixture.app(), fixture.root().resolve("index.html").toString()));
        assertErrorCode(ErrorCode.NO_AUTH_ERROR,
                () -> workspaceService.readFile(fixture.app(), ".GIT/config"));
        assertErrorCode(ErrorCode.OPERATION_ERROR,
                () -> workspaceService.readFile(fixture.app(), "archive.bin"));
    }

    @Test
    void shouldSaveHtmlFileAtomicallyWithoutTriggeringVueBuild() throws IOException {
        WorkspaceFixture fixture = createWorkspace(CodeGenTypeEnum.HTML);
        Path sourceFile = fixture.root().resolve("index.html");
        Files.writeString(sourceFile, "old", StandardCharsets.UTF_8);

        workspaceService.saveFile(fixture.app(), "index.html", "new content");

        assertEquals("new content", Files.readString(sourceFile, StandardCharsets.UTF_8));
        try (var children = Files.list(fixture.root())) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(".app-code-")));
        }
        verify(vueProjectBuilder, never()).buildProjectWithResult(anyString());
    }

    @Test
    void shouldRejectContentLargerThanOneMegabyte() throws IOException {
        WorkspaceFixture fixture = createWorkspace(CodeGenTypeEnum.HTML);
        Path sourceFile = fixture.root().resolve("index.html");
        Files.writeString(sourceFile, "old", StandardCharsets.UTF_8);
        String oversizedContent = "a".repeat(1024 * 1024 + 1);

        assertErrorCode(ErrorCode.OPERATION_ERROR,
                () -> workspaceService.saveFile(fixture.app(), "index.html", oversizedContent));

        assertEquals("old", Files.readString(sourceFile, StandardCharsets.UTF_8));
    }

    @Test
    void shouldRejectSavingWhenExistingFileExceedsEditLimit() throws IOException {
        WorkspaceFixture fixture = createWorkspace(CodeGenTypeEnum.HTML);
        Path sourceFile = fixture.root().resolve("large.js");
        Files.write(sourceFile, new byte[1024 * 1024 + 1]);

        assertErrorCode(ErrorCode.OPERATION_ERROR,
                () -> workspaceService.saveFile(fixture.app(), "large.js", "small"));

        assertEquals(1024 * 1024 + 1, Files.size(sourceFile));
    }

    @Test
    void shouldSkipWriteAndBuildWhenContentIsUnchanged() throws IOException {
        WorkspaceFixture fixture = createWorkspace(CodeGenTypeEnum.VUE_PROJECT);
        Path sourceFile = fixture.root().resolve("src").resolve("App.vue");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "<template>same</template>", StandardCharsets.UTF_8);

        workspaceService.saveFile(fixture.app(), "src/App.vue", "<template>same</template>");

        assertEquals("<template>same</template>", Files.readString(sourceFile, StandardCharsets.UTF_8));
        verify(vueProjectBuilder, never()).buildProjectWithResult(anyString());
    }
    @Test
    void shouldRestoreOriginalVueFileAndRebuildAfterBuildFailure() throws IOException {
        WorkspaceFixture fixture = createWorkspace(CodeGenTypeEnum.VUE_PROJECT);
        Path sourceFile = fixture.root().resolve("src").resolve("App.vue");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "<template>old</template>", StandardCharsets.UTF_8);
        VueProjectBuilder.BuildResult failedResult = new VueProjectBuilder.BuildResult(
                false, "build", fixture.root().toString(), "构建失败", null, null
        );
        VueProjectBuilder.BuildResult recoveredResult = new VueProjectBuilder.BuildResult(
                true, "done", fixture.root().toString(), "构建成功", null, null
        );
        when(vueProjectBuilder.buildProjectWithResult(anyString()))
                .thenReturn(failedResult, recoveredResult);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> workspaceService.saveFile(fixture.app(), "src/App.vue", "<template>broken</template>"));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertEquals("<template>old</template>", Files.readString(sourceFile, StandardCharsets.UTF_8));
        verify(vueProjectBuilder, org.mockito.Mockito.times(2))
                .buildProjectWithResult(fixture.root().toString());
    }

    @Test
    void shouldBuildFullStackFrontendDirectoryAfterSave() throws IOException {
        WorkspaceFixture fixture = createWorkspace(CodeGenTypeEnum.FULL_STACK_PROJECT);
        Path frontendRoot = fixture.root().resolve("frontend");
        Path sourceFile = frontendRoot.resolve("src").resolve("App.vue");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "<template>old</template>", StandardCharsets.UTF_8);
        when(vueProjectBuilder.buildProjectWithResult(anyString()))
                .thenReturn(new VueProjectBuilder.BuildResult(
                        true, "done", frontendRoot.toString(), "构建成功", null, null
                ));

        workspaceService.saveFile(fixture.app(), "frontend/src/App.vue", "<template>new</template>");

        assertEquals("<template>new</template>", Files.readString(sourceFile, StandardCharsets.UTF_8));
        verify(vueProjectBuilder).buildProjectWithResult(frontendRoot.toString());
    }

    @Test
    void shouldNeitherListNorReadSymbolicLinks() throws IOException {
        WorkspaceFixture fixture = createWorkspace(CodeGenTypeEnum.HTML);
        Path outsideFile = fixture.root().getParent().resolve("workspace-outside-" + fixture.app().getId() + ".html");
        Files.writeString(outsideFile, "outside", StandardCharsets.UTF_8);
        workspaceRoots.add(outsideFile);
        Path link = fixture.root().resolve("linked.html");
        try {
            Files.createSymbolicLink(link, outsideFile);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "当前平台不支持创建符号链接: " + e.getMessage());
        }

        List<AppCodeFileTreeVO> nodes = workspaceService.listFiles(fixture.app());

        assertTrue(nodes.stream().noneMatch(node -> "linked.html".equals(node.getName())));
        assertErrorCode(ErrorCode.NO_AUTH_ERROR,
                () -> workspaceService.readFile(fixture.app(), "linked.html"));
    }

    private WorkspaceFixture createWorkspace(CodeGenTypeEnum codeGenType) throws IOException {
        long appId = APP_ID_SEQUENCE.incrementAndGet();
        App app = new App();
        app.setId(appId);
        app.setCodeGenType(codeGenType.getValue());
        Path root = Path.of(
                AppConstant.CODE_OUTPUT_ROOT_DIR,
                codeGenType.getValue() + "_" + appId
        ).toAbsolutePath().normalize();
        Files.createDirectories(root);
        workspaceRoots.add(root);
        return new WorkspaceFixture(app, root);
    }

    private void assertErrorCode(ErrorCode expectedErrorCode, Runnable operation) {
        BusinessException exception = assertThrows(BusinessException.class, operation::run);
        assertEquals(expectedErrorCode.getCode(), exception.getCode());
    }

    private void deleteTreeWithoutFollowingLinks(Path root) throws IOException {
        if (!Files.exists(root) && !Files.isSymbolicLink(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private record WorkspaceFixture(App app, Path root) {
    }
}