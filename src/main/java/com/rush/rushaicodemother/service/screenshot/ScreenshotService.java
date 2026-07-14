package com.rush.rushaicodemother.service.screenshot;

/** 部署页面截图并上传对象存储的应用服务。 */
public interface ScreenshotService {

    /**
     * 当前运行环境是否启用了截图能力。
     *
     * @return 启用时返回 {@code true}
     */
    boolean isEnabled();

    /**
     * 生成并上传受信任部署页面的截图。
     *
     * @param webUrl 部署页面地址
     * @return 对象存储访问地址
     */
    String generateAndUploadScreenshot(String webUrl);
}
