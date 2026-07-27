package com.rush.rushaicodemother.mapper;

import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.model.entity.App;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 应用映射层。
 */
public interface AppMapper extends BaseMapper<App> {

    @Insert("""
            INSERT INTO app (
                appName, initPrompt, codeGenType, priority, userId, tenantId
            ) VALUES (
                #{appName}, #{initPrompt}, #{codeGenType}, #{priority}, #{userId}, #{tenantId}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertPreparedApp(App app);

    /** 查询一条未删除应用的完整元数据。 */
    @Select("""
            select id, appName, cover, initPrompt, codeGenType, deployKey, deployedTime,
                   isGenerating, generatingMessage, generatingStage, generatingTaskId,
                   generationLeaseUntil, devServerPort, priority,
                   userId, tenantId, editTime, createTime, updateTime, isDelete
            from app
            where id = #{appId} and isDelete = 0
            """)
    App selectActiveById(@Param("appId") Long appId);

    /** 仅更新未删除应用的名称和业务编辑时间。 */
    @Update("update app set appName = #{appName}, editTime = #{editTime} "
            + "where id = #{appId} and isDelete = 0")
    int updateActiveName(@Param("appId") Long appId,
                         @Param("appName") String appName,
                         @Param("editTime") LocalDateTime editTime);

    /** 仅当名称仍是创建时的初始值，才写入异步生成的标题。 */
    @Update("update app set appName = #{generatedName} "
            + "where id = #{appId} and isDelete = 0 "
            + "and binary appName = binary #{initialName} and editTime = createTime")
    int updateGeneratedNameIfUnchanged(@Param("appId") Long appId,
                                       @Param("initialName") String initialName,
                                       @Param("generatedName") String generatedName);

    /**
     * 更新管理员允许修改的字段；动态字段集合由 Mapper XML 固定定义，调用方不能扩展更新范围。
     */
    int updateActiveAdministrationFields(@Param("appId") Long appId,
                                         @Param("appName") String appName,
                                         @Param("cover") String cover,
                                         @Param("priority") Integer priority,
                                         @Param("editTime") LocalDateTime editTime);

    /** 仅更新未删除应用的 Dev Server 端口。 */
    @Update("update app set devServerPort = #{port} where id = #{appId} and isDelete = 0")
    int updateActiveDevServerPort(@Param("appId") Long appId, @Param("port") int port);

    /** 获取启动 Dev Server 所需的最小应用状态。 */
    @Select("select id, userId, tenantId, codeGenType, devServerPort from app "
            + "where id = #{appId} and isDelete = 0")
    App selectDevServerTarget(@Param("appId") Long appId);

    /** 获取复制锁内需要重新确认的最新源应用状态。 */
    @Select("select id, appName, cover, initPrompt, codeGenType, userId, tenantId from app "
            + "where id = #{appId} and isDelete = 0")
    App selectCopySourceState(@Param("appId") Long appId);

    /** 获取部署锁内需要重新确认的最新应用状态。 */
    @Select("select id, codeGenType, deployKey, deployedTime from app "
            + "where id = #{appId} and isDelete = 0")
    App selectDeploymentState(@Param("appId") Long appId);

    /** 更新部署元数据，只允许更新仍然有效的应用记录。 */
    @Update("update app set deployKey = #{deployKey}, deployedTime = #{deployedTime} "
            + "where id = #{appId} and isDelete = 0")
    int updateDeploymentMetadata(@Param("appId") Long appId,
                                 @Param("deployKey") String deployKey,
                                 @Param("deployedTime") LocalDateTime deployedTime);

    /**
     * 仅当部署版本仍是截图任务启动时的版本，才更新封面，避免较旧的异步任务覆盖新部署截图。
     */
    @Update("update app set cover = #{cover} where id = #{appId} and isDelete = 0 "
            + "and deployedTime = #{deployedTime}")
    int updateCoverForDeployment(@Param("appId") Long appId,
                                 @Param("deployedTime") LocalDateTime deployedTime,
                                 @Param("cover") String cover);
    /** 获取应用当前生成状态所有权，用于区分记录不存在与并发占用。 */
    @Select("select id, isGenerating, generatingTaskId, generationExecutionEpoch, generationLeaseUntil from app "
            + "where id = #{appId} and isDelete = 0")
    App selectGenerationState(@Param("appId") Long appId);

    /**
     * 原子认领应用生成状态。已有状态为空、已结束、租约过期或属于同一任务时允许认领。
     */
    @Update("""
            <script>
            update app
            set isGenerating = 1,
                generatingMessage = '',
                generatingStage = #{generatingStage},
                generatingTaskId = #{taskId},
                generationExecutionEpoch = #{executionEpoch},
                generationLeaseUntil = #{leaseUntil}
            where id = #{appId}
              and isDelete = 0
              and (
                    isGenerating = 0
                    or generatingTaskId is null
                    or generationLeaseUntil is null
                    or generationLeaseUntil &lt;= #{now}
                    or (generatingTaskId = #{taskId}
                        and generationExecutionEpoch = #{executionEpoch})
              )
            </script>
            """)
    int claimGenerationState(@Param("appId") Long appId,
                             @Param("taskId") String taskId,
                             @Param("executionEpoch") long executionEpoch,
                             @Param("generatingStage") String generatingStage,
                             @Param("targetCodeGenType") String targetCodeGenType,
                             @Param("now") LocalDateTime now,
                             @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 仅允许当前所有者更新生成阶段，并同时续租。 */
    @Update("""
            update app
            set isGenerating = 1,
                generatingStage = #{generatingStage},
                generatingMessage = #{generatingMessage},
                generationLeaseUntil = #{leaseUntil}
            where id = #{appId}
              and isDelete = 0
              and isGenerating = 1
              and generatingTaskId = #{taskId}
              and generationExecutionEpoch = #{executionEpoch}
            """)
    int updateOwnedGenerationStage(@Param("appId") Long appId,
                                   @Param("taskId") String taskId,
                                   @Param("executionEpoch") long executionEpoch,
                                   @Param("generatingStage") String generatingStage,
                                   @Param("generatingMessage") String generatingMessage,
                                   @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 仅允许当前所有者更新流式快照，并同时续租。 */
    @Update("""
            update app
            set isGenerating = 1,
                generatingMessage = #{generatingMessage},
                generationLeaseUntil = #{leaseUntil}
            where id = #{appId}
              and isDelete = 0
              and isGenerating = 1
              and generatingTaskId = #{taskId}
              and generationExecutionEpoch = #{executionEpoch}
            """)
    int updateOwnedGenerationSnapshot(@Param("appId") Long appId,
                                      @Param("taskId") String taskId,
                                      @Param("executionEpoch") long executionEpoch,
                                      @Param("generatingMessage") String generatingMessage,
                                      @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 仅允许当前任务所有者切换代码生成类型，并同时续租。 */
    @Update("""
            update app
            set codeGenType = #{codeGenType},
                generationLeaseUntil = #{leaseUntil}
            where id = #{appId}
              and isDelete = 0
              and isGenerating = 1
              and generatingTaskId = #{taskId}
              and generationExecutionEpoch = #{executionEpoch}
            """)
    int updateOwnedCodeGenType(@Param("appId") Long appId,
                                @Param("taskId") String taskId,
                                @Param("executionEpoch") long executionEpoch,
                                @Param("codeGenType") String codeGenType,
                               @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 只有当前任务所有者可以释放应用生成状态。 */
    @Update("""
            update app
            set isGenerating = 0,
                generatingMessage = '',
                generatingStage = null,
                generatingTaskId = null,
                generationExecutionEpoch = null,
                generationLeaseUntil = null
            where id = #{appId}
              and isDelete = 0
              and generatingTaskId = #{taskId}
              and generationExecutionEpoch = #{executionEpoch}
            """)
    int releaseOwnedGenerationState(@Param("appId") Long appId,
                                    @Param("taskId") String taskId,
                                    @Param("executionEpoch") long executionEpoch);

}
