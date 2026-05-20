package com.rush.rushaicodemother.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用代码文件保存请求
 */
@Data
public class AppCodeFileSaveRequest implements Serializable {

    /**
     * 应用 id
     */
    private Long appId;

    /**
     * 相对应用代码根目录的文件路径
     */
    private String filePath;

    /**
     * 文件内容
     */
    private String content;

    private static final long serialVersionUID = 1L;
}
