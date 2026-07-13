package com.rush.rushaicodemother.service.workspace;

import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.vo.AppCodeFileContentVO;
import com.rush.rushaicodemother.model.vo.AppCodeFileTreeVO;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** 本地文件系统应用代码工作区实现。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LocalAppCodeWorkspaceService implements AppCodeWorkspaceService {

    private static final long MAX_EDIT_FILE_SIZE = 1024 * 1024;
    private static final int MAX_FILE_TREE_DEPTH = 8;
    private static final String ATOMIC_TEMP_FILE_PREFIX = ".app-code-";

    private final GenerationWorkspaceService generationWorkspaceService;
    private final VueProjectBuilder vueProjectBuilder;

    @Override
    public List<AppCodeFileTreeVO> listFiles(App app) {
        GenerationWorkspace workspace = resolveWorkspace(app);
        if (!workspace.exists()) {
            return new ArrayList<>();
        }
        validateWorkspaceRoot(workspace);
        try (Stream<Path> children = Files.list(workspace.canonicalRootPath())) {
            return children
                    .filter(path -> isVisiblePath(workspace, path))
                    .sorted(fileComparator())
                    .map(path -> buildFileTreeNode(workspace, path, 1))
                    .toList();
        } catch (IOException e) {
            log.warn("读取应用代码文件树失败，appId: {}", app.getId(), e);
            return new ArrayList<>();
        }
    }

    @Override
    public AppCodeFileContentVO readFile(App app, String filePath) {
        GenerationWorkspace workspace = requireExistingWorkspace(app);
        Path targetFile = resolveExistingFile(workspace, filePath);
        ensureEditableFile(workspace, targetFile, "该文件类型不支持在线预览编辑");
        try {
            long fileSize = Files.size(targetFile);
            ThrowUtils.throwIf(fileSize > MAX_EDIT_FILE_SIZE,
                    ErrorCode.OPERATION_ERROR, "文件过大，不支持在线编辑");
            AppCodeFileContentVO contentVO = new AppCodeFileContentVO();
            contentVO.setPath(toRelativePath(workspace, targetFile));
            contentVO.setName(targetFile.getFileName().toString());
            contentVO.setContent(Files.readString(targetFile, StandardCharsets.UTF_8));
            contentVO.setSize(fileSize);
            contentVO.setEditable(true);
            return contentVO;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取应用代码文件失败");
        }
    }

    @Override
    public void saveFile(App app, String filePath, String content) {
        ThrowUtils.throwIf(content == null, ErrorCode.PARAMS_ERROR, "文件内容不能为空");
        ThrowUtils.throwIf(content.getBytes(StandardCharsets.UTF_8).length > MAX_EDIT_FILE_SIZE,
                ErrorCode.OPERATION_ERROR, "文件内容过大，不支持在线保存");
        GenerationWorkspace workspace = requireExistingWorkspace(app);
        Path targetFile = resolveExistingFile(workspace, filePath);
        ensureEditableFile(workspace, targetFile, "该文件类型不支持在线编辑");

        String originalContent;
        try {
            long originalFileSize = Files.size(targetFile);
            ThrowUtils.throwIf(originalFileSize > MAX_EDIT_FILE_SIZE,
                    ErrorCode.OPERATION_ERROR, "原文件过大，不支持在线保存");
            originalContent = Files.readString(targetFile, StandardCharsets.UTF_8);
            if (originalContent.equals(content)) {
                return;
            }
            writeUtf8Atomically(targetFile, content);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存应用代码文件失败");
        }

        try {
            rebuildIfRequired(workspace);
        } catch (BusinessException e) {
            rollbackSavedFile(workspace, targetFile, originalContent);
            throw new BusinessException(e.getCode(), "保存失败，代码未通过编译，已自动回退到上一次可用版本");
        } catch (Exception e) {
            rollbackSavedFile(workspace, targetFile, originalContent);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存失败，代码未通过编译，已自动回退到上一次可用版本");
        }
    }

    private GenerationWorkspace resolveWorkspace(App app) {
        ThrowUtils.throwIf(app == null || app.getId() == null, ErrorCode.PARAMS_ERROR, "应用不存在");
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        return generationWorkspaceService.resolve(app, codeGenType);
    }

    private GenerationWorkspace requireExistingWorkspace(App app) {
        GenerationWorkspace workspace = resolveWorkspace(app);
        ThrowUtils.throwIf(!workspace.exists(), ErrorCode.NOT_FOUND_ERROR, "应用代码不存在，请先生成代码");
        validateWorkspaceRoot(workspace);
        return workspace;
    }

    private void validateWorkspaceRoot(GenerationWorkspace workspace) {
        Path configuredRoot = workspace.rootPath().toAbsolutePath().normalize();
        ThrowUtils.throwIf(Files.isSymbolicLink(configuredRoot),
                ErrorCode.NO_AUTH_ERROR, "应用代码工作区不能是符号链接");
        ThrowUtils.throwIf(!workspace.canonicalRootPath().equals(configuredRoot),
                ErrorCode.NO_AUTH_ERROR, "应用代码工作区路径异常");
    }

    private Path resolveExistingFile(GenerationWorkspace workspace, String filePath) {
        ThrowUtils.throwIf(filePath == null || filePath.isBlank(), ErrorCode.PARAMS_ERROR, "文件路径不能为空");
        try {
            String normalizedInput = filePath.replace('\\', '/');
            Path relativePath = Path.of(normalizedInput);
            ThrowUtils.throwIf(relativePath.isAbsolute(), ErrorCode.NO_AUTH_ERROR, "非法文件路径");
            validateRelativeSegments(workspace, relativePath);

            Path rootPath = workspace.canonicalRootPath();
            Path targetPath = rootPath.resolve(relativePath).normalize();
            ThrowUtils.throwIf(!targetPath.startsWith(rootPath), ErrorCode.NO_AUTH_ERROR, "非法文件路径");
            rejectSymbolicLinks(rootPath, relativePath);
            ThrowUtils.throwIf(!Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS),
                    ErrorCode.NOT_FOUND_ERROR, "文件不存在");
            return targetPath;
        } catch (BusinessException e) {
            throw e;
        } catch (InvalidPathException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件路径格式错误");
        }
    }

    private void validateRelativeSegments(GenerationWorkspace workspace, Path relativePath) {
        for (Path segment : relativePath) {
            String segmentName = segment.toString();
            ThrowUtils.throwIf("..".equals(segmentName), ErrorCode.NO_AUTH_ERROR, "非法文件路径");
            ThrowUtils.throwIf(isHiddenName(workspace.hiddenFileNames(), segmentName)
                            || segmentName.startsWith(ATOMIC_TEMP_FILE_PREFIX),
                    ErrorCode.NO_AUTH_ERROR, "禁止访问该文件");
        }
    }

    private void rejectSymbolicLinks(Path rootPath, Path relativePath) {
        Path currentPath = rootPath;
        for (Path segment : relativePath) {
            currentPath = currentPath.resolve(segment);
            ThrowUtils.throwIf(Files.isSymbolicLink(currentPath),
                    ErrorCode.NO_AUTH_ERROR, "禁止通过符号链接访问文件");
        }
    }

    private AppCodeFileTreeVO buildFileTreeNode(GenerationWorkspace workspace, Path path, int depth) {
        AppCodeFileTreeVO node = new AppCodeFileTreeVO();
        boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        node.setName(path.getFileName().toString());
        node.setPath(toRelativePath(workspace, path));
        node.setDirectory(directory);
        node.setSize(directory ? 0L : safeFileSize(path));
        if (directory && depth < MAX_FILE_TREE_DEPTH) {
            node.setChildren(listChildNodes(workspace, path, depth + 1));
        }
        return node;
    }

    private List<AppCodeFileTreeVO> listChildNodes(GenerationWorkspace workspace, Path directory, int depth) {
        try (Stream<Path> children = Files.list(directory)) {
            return children
                    .filter(path -> isVisiblePath(workspace, path))
                    .sorted(fileComparator())
                    .map(path -> buildFileTreeNode(workspace, path, depth))
                    .toList();
        } catch (IOException e) {
            log.debug("读取代码子目录失败: {}", directory, e);
            return new ArrayList<>();
        }
    }

    private boolean isVisiblePath(GenerationWorkspace workspace, Path path) {
        String fileName = path.getFileName().toString();
        return !Files.isSymbolicLink(path)
                && !fileName.startsWith(ATOMIC_TEMP_FILE_PREFIX)
                && !isHiddenName(workspace.hiddenFileNames(), fileName);
    }

    private boolean isHiddenName(Set<String> hiddenNames, String fileName) {
        return hiddenNames.stream().anyMatch(hiddenName -> hiddenName.equalsIgnoreCase(fileName));
    }

    private Comparator<Path> fileComparator() {
        return Comparator
                .comparing((Path path) -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private long safeFileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private String toRelativePath(GenerationWorkspace workspace, Path path) {
        return workspace.canonicalRootPath().relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private void ensureEditableFile(GenerationWorkspace workspace, Path file, String errorMessage) {
        String fileName = file.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        ThrowUtils.throwIf(dotIndex < 0 || dotIndex == fileName.length() - 1,
                ErrorCode.OPERATION_ERROR, errorMessage);
        String extension = fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        ThrowUtils.throwIf(!workspace.editableExtensions().contains(extension),
                ErrorCode.OPERATION_ERROR, errorMessage);
    }

    private void rebuildIfRequired(GenerationWorkspace workspace) {
        CodeGenTypeEnum codeGenType = workspace.codeGenType();
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT && codeGenType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return;
        }
        Path buildRoot = codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT
                ? workspace.frontendRootPath()
                : workspace.canonicalRootPath();
        VueProjectBuilder.BuildResult buildResult = vueProjectBuilder.buildProjectWithResult(buildRoot.toString());
        ThrowUtils.throwIf(!buildResult.success(), ErrorCode.SYSTEM_ERROR, buildResult.toFailureSummary());
    }

    private void rollbackSavedFile(GenerationWorkspace workspace, Path targetFile, String originalContent) {
        try {
            writeUtf8Atomically(targetFile, originalContent);
            rebuildIfRequired(workspace);
        } catch (Exception e) {
            log.error("保存失败后回滚文件异常，appId: {}, file: {}", workspace.appId(), targetFile, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存失败且自动回退异常，请联系管理员处理");
        }
    }

    private void writeUtf8Atomically(Path targetFile, String content) throws IOException {
        Path parentDirectory = targetFile.getParent();
        Path temporaryFile = Files.createTempFile(parentDirectory, ATOMIC_TEMP_FILE_PREFIX, ".tmp");
        try {
            Files.copy(
                    targetFile,
                    temporaryFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
            );
            Files.writeString(
                    temporaryFile,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            try {
                Files.move(
                        temporaryFile,
                        targetFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }
}