package com.yupi.yuaicodemother.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用代码文件内容
 */
@Data
public class AppCodeFileContentVO implements Serializable {

    /**
     * 相对应用代码根目录的文件路径
     */
    private String path;

    /**
     * 文件名称
     */
    private String name;

    /**
     * 文件内容
     */
    private String content;

    /**
     * 文件大小
     */
    private Long size;

    /**
     * 是否可编辑
     */
    private Boolean editable;

    private static final long serialVersionUID = 1L;
}
