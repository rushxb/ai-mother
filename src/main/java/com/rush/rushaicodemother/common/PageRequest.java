package com.rush.rushaicodemother.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 公共分页请求。
 */
@Data
public class PageRequest {

    /** 当前页号，从 1 开始。 */
    @Min(1)
    private int pageNum = 1;

    /** 单页大小，统一限制以避免无界查询。 */
    @Min(1)
    @Max(100)
    private int pageSize = 10;

    /**
     * API 层排序字段名称。业务 Service 必须再映射到各资源的允许列，
     * 不得把该值原样作为数据库列名使用。
     */
    @Size(max = 50)
    private String sortField;

    /** 排序顺序。 */
    @Pattern(regexp = "ascend|descend", message = "排序顺序仅支持 ascend 或 descend")
    private String sortOrder = "descend";
}