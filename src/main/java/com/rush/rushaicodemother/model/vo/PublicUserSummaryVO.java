package com.rush.rushaicodemother.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 可公开展示的最小用户身份摘要。
 *
 * <p>该类型刻意不包含账号、平台角色、积分和时间字段；公开场景只能依赖此类型，
 * 从类型层面阻止管理字段被 Bean 拷贝或后续重构意外带出。</p>
 */
@Data
public class PublicUserSummaryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String userName;

    private String userAvatar;
}
