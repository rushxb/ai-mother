package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationTaskIdempotencyServiceTest {

    private final GenerationTaskIdempotencyService service = new GenerationTaskIdempotencyService();

    @Test
    void hashesMustBeStableWithoutRetainingTheRawHeader() {
        GenerationTaskIdempotency first = service.resolve("submission-key", 11L, "build dashboard");
        GenerationTaskIdempotency repeated = service.resolve("submission-key", 11L, "build dashboard");
        GenerationTaskIdempotency changedRequest =
                service.resolve("submission-key", 11L, "build another dashboard");

        assertEquals(first, repeated);
        assertEquals("3c228376442c9d8218810d9c35233150921132637b294022eabc2fe19febc99a",
                first.keyHash());
        assertEquals(first.keyHash(), changedRequest.keyHash());
        assertNotEquals(first.requestFingerprint(), changedRequest.requestFingerprint());
        assertTrue(first.present());
        assertFalse(first.toString().contains("submission-key"));
    }

    @Test
    void absentKeyMustDisableIdempotencyWhileMalformedKeysAreRejected() {
        assertEquals(GenerationTaskIdempotency.none(), service.resolve(null, 11L, "build dashboard"));
        assertThrows(BusinessException.class, () -> service.resolve("", 11L, "build dashboard"));
        assertThrows(BusinessException.class,
                () -> service.resolve("contains whitespace", 11L, "build dashboard"));
        assertThrows(BusinessException.class,
                () -> service.resolve("x".repeat(256), 11L, "build dashboard"));
    }
}
