package com.rush.rushaicodemother.service.storage;

/**
 * 对象存储端口。
 *
 * <p>业务代码只依赖项目自有上传契约，不感知腾讯云 COS 等供应商 SDK 类型。</p>
 */
public interface ObjectStorageService {

    /**
     * 上传本地普通文件并返回可访问地址。
     *
     * @param upload 已完成边界校验的上传命令
     * @return 对象公开访问地址
     */
    String upload(ObjectStorageUpload upload);
}
