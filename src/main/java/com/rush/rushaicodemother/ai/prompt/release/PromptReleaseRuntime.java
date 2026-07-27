package com.rush.rushaicodemother.ai.prompt.release;

import com.rush.rushaicodemother.ai.prompt.PromptCatalogSnapshot;

/** 用于以原子方式激活运行时目录中的持久释放指针的控制端口。 */
public interface PromptReleaseRuntime {

    PromptReleaseCapabilities capabilities();

    long activeRevision();

    PromptCatalogSnapshot preview(PromptReleaseState state);

    boolean activate(PromptReleaseState state);
}
