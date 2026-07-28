package com.rush.rushaicodemother.service.storage;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 对象上传命令。
 *
 * <p>在进入供应商适配器前统一收紧对象键和本地文件边界，避免目录语义、控制字符、
 * 符号链接或不存在的文件被不同适配器以不同方式处理。</p>
 */
public record ObjectStorageUpload(String objectKey, Path sourceFile) {

    private static final int MAX_OBJECT_KEY_LENGTH = 1_024;

    public ObjectStorageUpload {
        objectKey = normalizeObjectKey(objectKey);
        sourceFile = normalizeSourceFile(sourceFile);
    }

    /** 规范化{@code Object}键。 */
    private static String normalizeObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "对象键不能为空");
        }
        String normalized = objectKey.strip();
        int firstContentIndex = 0;
        while (firstContentIndex < normalized.length() && normalized.charAt(firstContentIndex) == '/') {
            firstContentIndex++;
        }
        normalized = normalized.substring(firstContentIndex);
        if (normalized.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "对象键不能为空");
        }
        if (normalized.length() > MAX_OBJECT_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "对象键长度超过限制");
        }
        if (normalized.indexOf('\\') >= 0 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "对象键包含非法字符");
        }
        boolean containsUnsafeSegment = Arrays.stream(normalized.split("/", -1))
                .anyMatch(segment -> segment.isEmpty() || ".".equals(segment) || "..".equals(segment));
        if (containsUnsafeSegment) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "对象键包含非法路径片段");
        }
        return normalized;
    }

    /** 规范化来源文件。 */
    private static Path normalizeSourceFile(Path sourceFile) {
        if (sourceFile == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待上传文件不能为空");
        }
        Path normalized = sourceFile.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待上传文件不存在或不是安全的普通文件");
        }
        return normalized;
    }
}
