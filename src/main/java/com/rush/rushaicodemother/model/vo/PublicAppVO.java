package com.rush.rushaicodemother.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 精选应用公开展示视图。
 *
 * <p>仅包含展示与访问已发布作品所需的字段，刻意排除原始提示词、生成状态、
 * Dev Server、数据库资源和管理字段。</p>
 */
@Data
public class PublicAppVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String appName;

    private String cover;

    private String codeGenType;

    private String deployKey;

    private LocalDateTime deployedTime;

    private Long userId;

    private LocalDateTime createTime;

    private PublicUserSummaryVO user;
}
