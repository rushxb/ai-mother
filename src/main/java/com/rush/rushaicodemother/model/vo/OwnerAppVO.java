package com.rush.rushaicodemother.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 登录租户成员或平台管理员可见的应用详情。 */
@Data
public class OwnerAppVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String appName;

    private String cover;

    private String initPrompt;

    private String codeGenType;

    private String deployKey;

    private LocalDateTime deployedTime;

    private Integer isGenerating;

    private String generatingMessage;

    private String generatingStage;

    private AppDatabaseResourceVO databaseResource;

    private Integer devServerPort;

    private Integer priority;

    private Long userId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private PublicUserSummaryVO user;
}
