package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;

/**
 * 表示指定用途的模型池当前没有可运行候选。
 *
 * <p>该异常是模型池解析 interface 中唯一允许调用方执行兼容降级的错误。
 * 数据库、密钥、配置解析等基础设施故障必须保留原异常向上抛出，禁止伪装成
 * “模型未配置”。</p>
 */
public final class AiModelPoolUnavailableException extends BusinessException {

    private final String modelType;

    public AiModelPoolUnavailableException(String modelType, String message) {
        super(ErrorCode.OPERATION_ERROR, message);
        this.modelType = modelType;
    }

    public String modelType() {
        return modelType;
    }
}
