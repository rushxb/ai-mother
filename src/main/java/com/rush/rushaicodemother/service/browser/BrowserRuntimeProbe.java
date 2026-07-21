package com.rush.rushaicodemother.service.browser;

import java.net.URI;
import java.time.Duration;

/** Opens a controlled local preview and returns bounded DOM, console and screenshot evidence. */
public interface BrowserRuntimeProbe {

    BrowserRuntimeObservation inspect(URI targetUri, Duration settleDelay);
}
