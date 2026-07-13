package com.rush.rushaicodemother.manager;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.rush.rushaicodemother.config.CosClientProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CosManagerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldNormalizeObjectKeyAndReturnJoinedUrl() throws Exception {
        COSClient cosClient = mock(COSClient.class);
        ObjectProvider<COSClient> provider = provider(cosClient);
        PutObjectResult result = new PutObjectResult();
        when(cosClient.putObject(org.mockito.ArgumentMatchers.any(PutObjectRequest.class))).thenReturn(result);
        CosManager manager = new CosManager(properties("https://cdn.example.com/"), provider);
        File file = Files.writeString(tempDirectory.resolve("screenshot.jpg"), "image").toFile();

        String url = manager.uploadFile("/screenshots/2026/07/image.jpg", file);

        assertEquals("https://cdn.example.com/screenshots/2026/07/image.jpg", url);
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient).putObject(requestCaptor.capture());
        assertEquals("screenshots/2026/07/image.jpg", requestCaptor.getValue().getKey());
        assertEquals("bucket-123", requestCaptor.getValue().getBucketName());
    }

    @Test
    void shouldRejectMissingFileBeforeAccessingClient() {
        ObjectProvider<COSClient> provider = provider(mock(COSClient.class));
        CosManager manager = new CosManager(properties("https://cdn.example.com"), provider);

        assertThrows(
                BusinessException.class,
                () -> manager.uploadFile("screenshots/missing.jpg", tempDirectory.resolve("missing.jpg").toFile())
        );
    }

    @Test
    void shouldFailWhenCosIsDisabledOrClientReturnsNoResult() throws Exception {
        File file = Files.writeString(tempDirectory.resolve("screenshot.jpg"), "image").toFile();
        CosManager disabledManager = new CosManager(properties("https://cdn.example.com"), provider(null));
        assertThrows(BusinessException.class, () -> disabledManager.uploadFile("screenshots/image.jpg", file));

        COSClient cosClient = mock(COSClient.class);
        when(cosClient.putObject(org.mockito.ArgumentMatchers.any(PutObjectRequest.class))).thenReturn(null);
        CosManager failingManager = new CosManager(properties("https://cdn.example.com"), provider(cosClient));
        assertThrows(BusinessException.class, () -> failingManager.uploadFile("screenshots/image.jpg", file));
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<COSClient> provider(COSClient cosClient) {
        ObjectProvider<COSClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(cosClient);
        return provider;
    }

    private CosClientProperties properties(String host) {
        CosClientProperties properties = new CosClientProperties();
        properties.setEnabled(true);
        properties.setHost(host);
        properties.setBucket("bucket-123");
        return properties;
    }
}
