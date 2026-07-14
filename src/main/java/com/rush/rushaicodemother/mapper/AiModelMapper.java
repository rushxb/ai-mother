package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.AiModel;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** AI 模型配置显式 SQL 映射。 */
public interface AiModelMapper {

    @Select("""
            SELECT id, modelName, provider, modelId, description, baseUrl, apiKey,
                   maxTokens, temperature, isEnabled, modelType, supportsThinking,
                   sortOrder, configJson, userId, editTime, createTime, updateTime, isDelete
            FROM ai_model
            WHERE id = #{modelId}
              AND isDelete = 0
            """)
    AiModel selectActiveById(@Param("modelId") Long modelId);

    @Select("""
            SELECT id, modelName, provider, modelId, description, baseUrl, apiKey,
                   maxTokens, temperature, isEnabled, modelType, supportsThinking,
                   sortOrder, configJson, userId, editTime, createTime, updateTime, isDelete
            FROM ai_model
            WHERE id = #{modelId}
              AND isDelete = 0
            FOR UPDATE
            """)
    AiModel selectActiveByIdForUpdate(@Param("modelId") Long modelId);

    @Select("""
            <script>
            SELECT id, modelName, provider, modelId, description, baseUrl, apiKey,
                   maxTokens, temperature, isEnabled, modelType, supportsThinking,
                   sortOrder, configJson, userId, editTime, createTime, updateTime, isDelete
            FROM ai_model
            WHERE isEnabled = 1
              AND isDelete = 0
            <if test="modelType != null and modelType != ''">
              AND modelType = #{modelType}
            </if>
            ORDER BY sortOrder ASC, id ASC
            </script>
            """)
    List<AiModel> selectEnabled(@Param("modelType") String modelType);

    @Select("""
            SELECT id
            FROM ai_model
            WHERE provider = #{provider}
              AND modelId = #{modelId}
              AND isDelete = 0
            LIMIT 1
            """)
    Long selectActiveIdentityId(@Param("provider") String provider,
                                @Param("modelId") String modelId);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM ai_model
            WHERE isDelete = 0
            <if test="provider != null and provider != ''">
              AND provider = #{provider}
            </if>
            <if test="modelType != null and modelType != ''">
              AND modelType = #{modelType}
            </if>
            <if test="isEnabled != null">
              AND isEnabled = #{isEnabled}
            </if>
            <if test="keyword != null and keyword != ''">
              AND (modelName LIKE CONCAT('%', #{keyword}, '%')
                   OR modelId LIKE CONCAT('%', #{keyword}, '%')
                   OR description LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            </script>
            """)
    long countActive(@Param("provider") String provider,
                     @Param("modelType") String modelType,
                     @Param("isEnabled") Integer isEnabled,
                     @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT id, modelName, provider, modelId, description, baseUrl, apiKey,
                   maxTokens, temperature, isEnabled, modelType, supportsThinking,
                   sortOrder, configJson, userId, editTime, createTime, updateTime, isDelete
            FROM ai_model
            WHERE isDelete = 0
            <if test="provider != null and provider != ''">
              AND provider = #{provider}
            </if>
            <if test="modelType != null and modelType != ''">
              AND modelType = #{modelType}
            </if>
            <if test="isEnabled != null">
              AND isEnabled = #{isEnabled}
            </if>
            <if test="keyword != null and keyword != ''">
              AND (modelName LIKE CONCAT('%', #{keyword}, '%')
                   OR modelId LIKE CONCAT('%', #{keyword}, '%')
                   OR description LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY ${sortColumn} ${sortDirection}, id DESC
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<AiModel> selectActivePage(@Param("provider") String provider,
                                   @Param("modelType") String modelType,
                                   @Param("isEnabled") Integer isEnabled,
                                   @Param("keyword") String keyword,
                                   @Param("sortColumn") String sortColumn,
                                   @Param("sortDirection") String sortDirection,
                                   @Param("pageSize") Integer pageSize,
                                   @Param("offset") Long offset);

    @Insert("""
            INSERT INTO ai_model (
                modelName, provider, modelId, description, baseUrl, apiKey,
                maxTokens, temperature, isEnabled, modelType, supportsThinking,
                sortOrder, configJson, userId, isDelete
            ) VALUES (
                #{modelName}, #{provider}, #{modelId}, #{description}, #{baseUrl}, #{apiKey},
                #{maxTokens}, #{temperature}, #{isEnabled}, #{modelType}, #{supportsThinking},
                #{sortOrder}, #{configJson}, #{userId}, 0
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertModel(AiModel model);

    @Update("""
            UPDATE ai_model
            SET modelName = #{modelName},
                provider = #{provider},
                modelId = #{modelId},
                description = #{description},
                baseUrl = #{baseUrl},
                apiKey = #{apiKey},
                maxTokens = #{maxTokens},
                temperature = #{temperature},
                isEnabled = #{isEnabled},
                modelType = #{modelType},
                supportsThinking = #{supportsThinking},
                sortOrder = #{sortOrder},
                configJson = #{configJson},
                editTime = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND isDelete = 0
            """)
    int updateActiveModel(AiModel model);

    @Update("""
            <script>
            UPDATE ai_model
            SET isEnabled = 0,
                editTime = CURRENT_TIMESTAMP
            WHERE modelType = #{modelType}
              AND isEnabled = 1
              AND isDelete = 0
            <if test="excludedModelId != null">
              AND id != #{excludedModelId}
            </if>
            </script>
            """)
    int disableOtherEnabledModels(@Param("modelType") String modelType,
                                  @Param("excludedModelId") Long excludedModelId);

    @Update("""
            UPDATE ai_model
            SET isEnabled = 0,
                isDelete = 1,
                editTime = CURRENT_TIMESTAMP
            WHERE id = #{modelId}
              AND isDelete = 0
            """)
    int logicallyDeleteActiveModel(@Param("modelId") Long modelId);
}
