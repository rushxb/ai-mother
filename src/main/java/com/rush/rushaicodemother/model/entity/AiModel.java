package com.rush.rushaicodemother.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

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
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 模型显示名称
     */
    private String modelName;

    /**
     * 模型提供商：deepseek/openai/custom
     */
    private String provider;

    /**
     * 模型标识符，如 deepseek-v4-flash
     */
    private String modelId;

    /**
     * 模型描述
     */
    private String description;

    /**
     * API 基础地址
     */
    private String baseUrl;

    /**
     * API 密钥
     */
    private String apiKey;

    /**
     * 最大 token 数
     */
    private Integer maxTokens;

    /**
     * 温度参数
     */
    private Double temperature;

    /**
     * 是否启用：0-禁用 1-启用
     */
    private Integer isEnabled;

    /**
     * 模型类型：chat/reasoning/routing
     */
    private String modelType;

    /**
     * 是否支持 thinking 模式：0-不支持 1-支持
     */
    private Integer supportsThinking;

    /**
     * 排序权重
     */
    private Integer sortOrder;

    /**
     * 扩展配置 JSON
     */
    private String configJson;

    /**
     * 创建用户id
     */
    private Long userId;

    /**
     * 编辑时间
     */
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
