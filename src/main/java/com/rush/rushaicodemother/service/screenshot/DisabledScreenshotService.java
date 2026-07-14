package com.rush.rushaicodemother.service.screenshot;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;

/** 截图功能关闭时的显式实现，避免引入可选依赖查找。 */
public final class DisabledScreenshotService implements ScreenshotService {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String generateAndUploadScreenshot(String webUrl) {
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "当前运行环境未启用网页截图功能");
    }
}
