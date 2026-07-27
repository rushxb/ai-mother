package com.rush.rushaicodemother.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 模型配置实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("ai_model")
public class AiModel implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 编号
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 模型显示名称
     */
    @Column("modelName")
    private String modelName;

    /**
     * 模型提供商：deepseek/openai/custom
     */
    @Column("provider")
    private String provider;

    /**
     * 模型标识符，如 deepseek-v4-flash
     */
    @Column("modelId")
    private String modelId;

    /**
     * 模型描述
     */
    @Column("description")
    private String description;

    /**
     * API 基础地址
     */
    @Column("baseUrl")
    private String baseUrl;

    /**
     * API 密钥
     */
    @Column("secretRef")
    @ToString.Exclude
    private String secretRef;

    @Column("secretFingerprint")
    @ToString.Exclude
    private String secretFingerprint;

    @Column("secretKeyId")
    private String secretKeyId;

    /**
     * 最大 token 数
     */
    @Column("maxTokens")
    private Integer maxTokens;

    /**
     * 温度参数
     */
    @Column("temperature")
    private Double temperature;

    /**
     * 是否启用：0-禁用 1-启用
     */
    @Column("isEnabled")
    private Integer isEnabled;

    /**
     * 模型类型：chat/reasoning/routing
     */
    @Column("modelType")
    private String modelType;

    /**
     * 是否支持 thinking 模式：0-不支持 1-支持
     */
    @Column("supportsThinking")
    private Integer supportsThinking;

    /**
     * 排序权重
     */
    @Column("sortOrder")
    private Integer sortOrder;

    /**
     * 扩展配置 JSON
     */
    @Column("configJson")
    private String configJson;

    /**
     * 创建用户id
     */
    @Column("userId")
    private Long userId;

    /**
     * 编辑时间
     */
    @Column("editTime")
    private LocalDateTime editTime;

    /**
     * 创建时间
     */
    @Column("createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;

    /**
     * 是否删除
     */
    @Column("isDelete")
    private Integer isDelete;
}
