package com.yupi.yuaicodemother.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用代码文件树节点
 */
@Data
public class AppCodeFileTreeVO implements Serializable {

    /**
     * 展示名称
     */
    private String name;

    /**
     * 相对应用代码根目录的路径
     */
    private String path;

    /**
     * 是否目录
     */
    private Boolean directory;

    /**
     * 文件大小
     */
    private Long size;

    /**
     * 子节点
     */
    private List<AppCodeFileTreeVO> children = new ArrayList<>();

    private static final long serialVersionUID = 1L;
}
