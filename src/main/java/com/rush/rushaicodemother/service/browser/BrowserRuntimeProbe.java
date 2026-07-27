package com.rush.rushaicodemother.service.browser;

import java.net.URI;
import java.time.Duration;

/** 打开受控的本地预览并返回有界 DOM、控制台和屏幕截图证据。 */
public interface BrowserRuntimeProbe {

    BrowserRuntimeObservation inspect(URI targetUri, Duration settleDelay);
}
