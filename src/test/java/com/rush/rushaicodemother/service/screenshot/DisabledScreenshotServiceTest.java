package com.rush.rushaicodemother.service.screenshot;

import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisabledScreenshotServiceTest {

    @Test
    void shouldExposeDisabledStateAndRejectDirectInvocation() {
        ScreenshotService screenshotService = new DisabledScreenshotService();

        assertFalse(screenshotService.isEnabled());
        assertThrows(
                BusinessException.class,
                () -> screenshotService.generateAndUploadScreenshot("http://localhost:91/app/")
        );
    }
}
