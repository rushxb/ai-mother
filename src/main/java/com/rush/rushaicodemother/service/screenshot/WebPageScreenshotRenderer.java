package com.rush.rushaicodemother.service.screenshot;

import java.net.URI;
import java.nio.file.Path;

/** 将网页渲染为本地截图文件的基础设施端口。 */
public interface WebPageScreenshotRenderer {

    /**
     * 在调用方拥有的工作目录内生成截图。
     *
     * @param targetUri 经过业务边界校验的目标地址
     * @param workspace 本次截图独占的工作目录
     * @return 工作目录内的最终截图文件
     */
    Path render(URI targetUri, Path workspace);
}
