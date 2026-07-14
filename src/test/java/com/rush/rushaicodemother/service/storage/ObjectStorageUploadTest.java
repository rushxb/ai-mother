package com.rush.rushaicodemother.service.storage;

import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectStorageUploadTest {

    private Path tempDirectory;
    private Path sourceFile;

    @BeforeEach
    void setUpWorkspace() throws IOException {
        tempDirectory = Path.of("target", "test-workspaces", "object-storage-upload")
                .toAbsolutePath()
                .normalize();
        deleteRecursively(tempDirectory);
        Files.createDirectories(tempDirectory);
        sourceFile = Files.writeString(tempDirectory.resolve("image.jpg"), "image");
    }

    @AfterEach
    void cleanUpWorkspace() throws IOException {
        deleteRecursively(tempDirectory);
    }

    @Test
    void shouldNormalizeLeadingSlashesAndSourcePath() {
        ObjectStorageUpload upload = new ObjectStorageUpload(
                "  ///screenshots/2026/07/image.jpg  ",
                sourceFile
        );

        assertEquals("screenshots/2026/07/image.jpg", upload.objectKey());
        assertEquals(sourceFile.toAbsolutePath().normalize(), upload.sourceFile());
    }

    @Test
    void shouldRejectUnsafeObjectKeys() {
        assertThrows(BusinessException.class, () -> new ObjectStorageUpload("../image.jpg", sourceFile));
        assertThrows(BusinessException.class, () -> new ObjectStorageUpload("screenshots//image.jpg", sourceFile));
        assertThrows(BusinessException.class, () -> new ObjectStorageUpload("screenshots\\image.jpg", sourceFile));
        assertThrows(BusinessException.class, () -> new ObjectStorageUpload("screenshots/ima\u0000ge.jpg", sourceFile));
    }

    @Test
    void shouldRejectMissingOrNonRegularSourceFile() {
        assertThrows(
                BusinessException.class,
                () -> new ObjectStorageUpload("screenshots/missing.jpg", tempDirectory.resolve("missing.jpg"))
        );
        assertThrows(
                BusinessException.class,
                () -> new ObjectStorageUpload("screenshots/directory.jpg", tempDirectory)
        );
    }

    @Test
    void shouldRejectSymbolicLinkSourceFile() throws IOException {
        Path symbolicLink = tempDirectory.resolve("linked-image.jpg");
        try {
            Files.createSymbolicLink(symbolicLink, sourceFile);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(
                    false,
                    "The current environment does not permit symbolic-link creation: " + exception.getMessage()
            );
        }

        assertThrows(
                BusinessException.class,
                () -> new ObjectStorageUpload("screenshots/linked-image.jpg", symbolicLink)
        );
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
