package com.rush.rushaicodemother.ai.prompt.release;

/** Control port for atomically activating durable release pointers in the runtime catalog. */
public interface PromptReleaseRuntime {

    PromptReleaseCapabilities capabilities();

    long activeRevision();

    boolean activate(PromptReleaseState state);
}
