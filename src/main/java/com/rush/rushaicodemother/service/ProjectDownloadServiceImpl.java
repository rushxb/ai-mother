package com.rush.rushaicodemother.service;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 项目源码压缩下载服务。
 *
 * <p>压缩过程不跟随符号链接，并对每个归档条目执行真实路径和名称校验，
 * 防止通过软链接、路径穿越或响应头注入读取非项目文件。</p>
 */
@Service
@Slf4j
public class ProjectDownloadServiceImpl implements ProjectDownloadService {

    private static final Pattern DOWNLOAD_FILE_NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    private static final Set<String> IGNORED_NAMES = Set.of(
            "node_modules",
            ".git",
            "dist",
            "build",
            ".ds_store",
            ".env",
            "target",
            ".mvn",
            ".idea",
            ".vscode"
    );

    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".log",
            ".tmp",
            ".cache"
    );

    @Override
    public void downloadProjectAsZip(String projectPath,
                                     String downloadFileName,
                                     HttpServletResponse response) {
        ThrowUtils.throwIf(StrUtil.isBlank(projectPath),
                ErrorCode.PARAMS_ERROR, "项目路径不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(downloadFileName),
                ErrorCode.PARAMS_ERROR, "下载文件名不能为空");
        ThrowUtils.throwIf(!DOWNLOAD_FILE_NAME_PATTERN.matcher(downloadFileName).matches(),
                ErrorCode.PARAMS_ERROR, "下载文件名不合法");
        ThrowUtils.throwIf(response == null, ErrorCode.PARAMS_ERROR, "HTTP 响应不能为空");

        Path projectRoot = resolveProjectRoot(projectPath);
        configureResponse(response, downloadFileName);
        log.info("开始打包下载项目: {} -> {}.zip", projectRoot, downloadFileName);

        try (ZipOutputStream zipOutputStream = new ZipOutputStream(
                response.getOutputStream(), StandardCharsets.UTF_8)) {
            Files.walkFileTree(projectRoot, new ProjectZipVisitor(projectRoot, zipOutputStream));
            zipOutputStream.finish();
            log.info("打包下载项目成功: {} -> {}.zip", projectRoot, downloadFileName);
        } catch (IOException exception) {
            log.error("打包下载项目失败: {}", projectRoot, exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "打包下载项目失败");
        }
    }

    private Path resolveProjectRoot(String projectPath) {
        try {
            Path declaredRoot = Path.of(projectPath).toAbsolutePath().normalize();
            Path realRoot = declaredRoot.toRealPath();
            ThrowUtils.throwIf(!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS),
                    ErrorCode.PARAMS_ERROR, "项目路径不是一个目录");
            return realRoot;
        } catch (InvalidPathException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "项目路径不合法");
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "项目路径不存在或不可访问");
        }
    }

    private void configureResponse(HttpServletResponse response, String downloadFileName) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition",
                String.format("attachment; filename=\"%s.zip\"", downloadFileName));
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
    }

    private boolean isPathAllowed(Path projectRoot, Path candidate) {
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(projectRoot)) {
            return false;
        }
        Path relativePath = projectRoot.relativize(normalizedCandidate);
        for (Path part : relativePath) {
            String normalizedName = part.toString().toLowerCase(Locale.ROOT);
            if (IGNORED_NAMES.contains(normalizedName)) {
                return false;
            }
            if (IGNORED_EXTENSIONS.stream().anyMatch(normalizedName::endsWith)) {
                return false;
            }
        }
        return true;
    }

    private final class ProjectZipVisitor extends SimpleFileVisitor<Path> {

        private final Path projectRoot;
        private final ZipOutputStream zipOutputStream;

        private ProjectZipVisitor(Path projectRoot, ZipOutputStream zipOutputStream) {
            this.projectRoot = projectRoot;
            this.zipOutputStream = zipOutputStream;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
            if (!directory.equals(projectRoot)
                    && (Files.isSymbolicLink(directory) || !isPathAllowed(projectRoot, directory))) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            Path realDirectory = directory.toRealPath();
            return realDirectory.startsWith(projectRoot)
                    ? FileVisitResult.CONTINUE
                    : FileVisitResult.SKIP_SUBTREE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            if (Files.isSymbolicLink(file)
                    || !attributes.isRegularFile()
                    || !isPathAllowed(projectRoot, file)) {
                return FileVisitResult.CONTINUE;
            }
            Path realFile = file.toRealPath();
            if (!realFile.startsWith(projectRoot)
                    || !Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)) {
                return FileVisitResult.CONTINUE;
            }
            writeEntry(realFile, attributes);
            return FileVisitResult.CONTINUE;
        }

        private void writeEntry(Path realFile, BasicFileAttributes attributes) throws IOException {
            String entryName = projectRoot.relativize(realFile)
                    .toString()
                    .replace('\\', '/');
            if (entryName.isBlank() || entryName.startsWith("/") || entryName.contains("../")) {
                throw new IOException("非法 ZIP 条目路径: " + entryName);
            }
            ZipEntry entry = new ZipEntry(entryName);
            entry.setLastModifiedTime(attributes.lastModifiedTime());
            zipOutputStream.putNextEntry(entry);
            try (InputStream inputStream = Files.newInputStream(realFile, LinkOption.NOFOLLOW_LINKS)) {
                inputStream.transferTo(zipOutputStream);
            } finally {
                zipOutputStream.closeEntry();
            }
        }
    }
}