package com.rush.rushaicodemother.infrastructure.storage.cos;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.rush.rushaicodemother.config.CosClientProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.service.storage.ObjectStorageService;
import com.rush.rushaicodemother.service.storage.ObjectStorageUpload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/** 腾讯云 COS 对象存储适配器。 */
@Slf4j
public final class TencentCosObjectStorageService implements ObjectStorageService {

    private final COSClient cosClient;
    private final String bucket;
    private final String publicHost;

    public TencentCosObjectStorageService(COSClient cosClient, CosClientProperties properties) {
        this.cosClient = cosClient;
        this.bucket = properties.getBucket().trim();
        this.publicHost = stripTrailingSlash(properties.getHost().trim());
    }

    @Override
    public String upload(ObjectStorageUpload upload) {
        if (upload == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "对象上传参数不能为空");
        }
        PutObjectRequest request = new PutObjectRequest(
                bucket, upload.objectKey(), upload.sourceFile().toFile());
        try {
            PutObjectResult result = cosClient.putObject(request);
            if (result == null) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "对象存储上传失败");
            }
            return publicHost + "/" + UriUtils.encodePath(upload.objectKey(), StandardCharsets.UTF_8);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("腾讯云 COS 上传失败，objectKey={}", upload.objectKey(), LogExceptionSanitizer.sanitize(exception));
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "对象存储上传失败，请稍后重试", exception);
        }
    }

    private static String stripTrailingSlash(String host) {
        int endIndex = host.length();
        while (endIndex > 0 && host.charAt(endIndex - 1) == '/') {
            endIndex--;
        }
        return host.substring(0, endIndex);
    }
}
