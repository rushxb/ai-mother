package com.rush.rushaicodemother.manager;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.rush.rushaicodemother.config.CosClientProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * COS 对象存储管理器
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CosManager {

    private final CosClientProperties properties;

    private final ObjectProvider<COSClient> cosClientProvider;

    /**
     * 上传对象
     *
     * @param key  唯一键
     * @param file 文件
     * @return 上传结果
     */
    public PutObjectResult putObject(String key, File file) {
        validateUploadArguments(key, file);
        COSClient cosClient = cosClientProvider.getIfAvailable();
        if (cosClient == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "对象存储未配置，无法上传文件");
        }
        PutObjectRequest putObjectRequest = new PutObjectRequest(properties.getBucket(), normalizeKey(key), file);
        PutObjectResult result = cosClient.putObject(putObjectRequest);
        if (result == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "对象存储上传失败");
        }
        return result;
    }

    /**
     * 上传文件到 COS 并返回访问 URL
     *
     * @param key  COS对象键（完整路径）
     * @param file 要上传的文件
     * @return 文件访问 URL
     */
    public String uploadFile(String key, File file) {
        putObject(key, file);
        String url = joinObjectUrl(properties.getHost(), key);
        log.info("文件上传到 COS 成功：{} -> {}", file.getName(), url);
        return url;
    }

    private void validateUploadArguments(String key, File file) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "对象键不能为空");
        }
        if (file == null || !file.isFile()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待上传文件不存在或不是普通文件");
        }
    }

    private String joinObjectUrl(String host, String key) {
        String normalizedHost = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        return normalizedHost + "/" + normalizeKey(key);
    }

    private String normalizeKey(String key) {
        int firstContentIndex = 0;
        while (firstContentIndex < key.length() && key.charAt(firstContentIndex) == '/') {
            firstContentIndex++;
        }
        String normalizedKey = key.substring(firstContentIndex);
        if (normalizedKey.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "对象键不能为空");
        }
        return normalizedKey;
    }
}
