package com.rush.rushaicodemother.common;

import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 标准 API 响应信封。
 *
 * @param <T> 响应数据类型
 */
@Data
public class BaseResponse<T> implements Serializable {

    private int code;

    private T data;

    private String message;

    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public BaseResponse(int code, T data) {
        this(code, data, "");
    }

    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }
}
