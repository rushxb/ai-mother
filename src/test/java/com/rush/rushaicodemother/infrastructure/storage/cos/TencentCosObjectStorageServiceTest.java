package com.rush.rushaicodemother.infrastructure.storage.cos;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.rush.rushaicodemother.config.CosClientProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.service.storage.ObjectStorageUpload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TencentCosObjectStorageServiceTest {

    private Path tempDirectory;
    private Path sourceFile;
    private COSClient cosClient;
    private TencentCosObjectStorageService storageService;

    @BeforeEach
    void setUp() throws IOException {
        tempDirectory = Path.of("target", "test-workspaces", "tencent-cos-storage")
                .toAbsolutePath()
                .normalize();
        deleteRecursively(tempDirectory);
        Files.createDirectories(tempDirectory);
        sourceFile = Files.writeString(tempDirectory.resolve("image.jpg"), "image");
        cosClient = mock(COSClient.class);
        storageService = new TencentCosObjectStorageService(cosClient, properties());
    }

    @AfterEach
    void cleanUp() throws IOException {
        deleteRecursively(tempDirectory);
    }

    @Test
    void shouldUploadToConfiguredBucketAndReturnEncodedPublicUrl() {
        when(cosClient.putObject(any(PutObjectRequest.class))).thenReturn(new PutObjectResult());
        ObjectStorageUpload upload = new ObjectStorageUpload("screenshots/中文 image.jpg", sourceFile);

        String publicUrl = storageService.upload(upload);

        assertEquals(
                "https://cdn.example.com/screenshots/%E4%B8%AD%E6%96%87%20image.jpg",
                publicUrl
        );
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient).putObject(requestCaptor.capture());
        PutObjectRequest request = requestCaptor.getValue();
        assertEquals("bucket-123", request.getBucketName());
        assertEquals("screenshots/中文 image.jpg", request.getKey());
        assertEquals(sourceFile.toFile(), request.getFile());
    }

    @Test
    void shouldRejectNullUploadBeforeCallingCos() {
        assertThrows(BusinessException.class, () -> storageService.upload(null));
    }

    @Test
    void shouldFailWhenCosReturnsNoResult() {
        when(cosClient.putObject(any(PutObjectRequest.class))).thenReturn(null);

        assertThrows(
                BusinessException.class,
                () -> storageService.upload(new ObjectStorageUpload("screenshots/image.jpg", sourceFile))
        );
    }

    @Test
    void shouldMapCosRuntimeFailureToSafeBusinessException() {
        IllegalStateException cosFailure = new IllegalStateException("provider detail");
        when(cosClient.putObject(any(PutObjectRequest.class))).thenThrow(cosFailure);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> storageService.upload(new ObjectStorageUpload("screenshots/image.jpg", sourceFile))
        );

        assertEquals("对象存储上传失败，请稍后重试", exception.getMessage());
        assertSame(cosFailure, exception.getCause());
    }

    private CosClientProperties properties() {
        CosClientProperties properties = new CosClientProperties();
        properties.setEnabled(true);
        properties.setHost("https://cdn.example.com/");
        properties.setBucket("bucket-123");
        return properties;
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
