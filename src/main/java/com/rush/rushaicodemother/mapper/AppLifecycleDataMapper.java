package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.App;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 应用生命周期关联数据映射层。
 *
 * <p>每个关联表使用固定 SQL，避免运行时拼接表名，并使数据库清理范围可审计、可测试。</p>
 */
public interface AppLifecycleDataMapper {

    /**
     * 在删除事务内锁定应用，并加载删除决策所需的全部事实。
     *
     * <p>字段必须显式列全：删除流程既要创建租户级记忆清理请求，也要拒绝仍被生成任务
     * 持有的应用。使用 {@code FOR UPDATE} 与生成任务提交时的应用行锁串行，避免检查通过后
     * 又插入一条已预授权的排队任务。</p>
     */
    @Select("""
            select id, userId, tenantId, codeGenType, deployKey,
                   isGenerating, generatingTaskId, generationLeaseUntil,
                   generationExecutionEpoch
            from app
            where id = #{appId} and isDelete = 0
            for update
            """)
    App selectDeletionState(@Param("appId") Long appId);

    /** 统计会被应用删除破坏结算或恢复语义的非终态生成任务。 */
    @Select("""
            select count(*)
            from generation_task
            where appId = #{appId}
              and status not in ('success', 'failed', 'cancelled', 'deadline_exceeded')
              and isDelete = 0
            """)
    int countNonTerminalGenerationTasks(@Param("appId") Long appId);

    @Delete("delete from generation_model_call where appId = #{appId}")
    int deleteGenerationModelCalls(@Param("appId") Long appId);

    @Delete("delete from generation_build_log where appId = #{appId}")
    int deleteGenerationBuildLogs(@Param("appId") Long appId);

    @Delete("delete span from generation_task_span span "
            + "inner join generation_task task on task.taskId = span.taskId "
            + "where task.appId = #{appId}")
    int deleteGenerationTaskSpans(@Param("appId") Long appId);

    @Delete("delete from generation_tool_approval where appId = #{appId}")
    int deleteGenerationToolApprovals(@Param("appId") Long appId);

    @Delete("delete from generation_task where appId = #{appId}")
    int deleteGenerationTasks(@Param("appId") Long appId);

    @Delete("delete from chat_history where appId = #{appId}")
    int deleteChatHistory(@Param("appId") Long appId);

    @Delete("delete from app_capability where appId = #{appId}")
    int deleteCapabilities(@Param("appId") Long appId);

    @Delete("delete from app_database_resource where appId = #{appId}")
    int deleteDatabaseResources(@Param("appId") Long appId);

    @Delete("delete from app_git_repository where appId = #{appId}")
    int deleteGitRepositories(@Param("appId") Long appId);

    @Delete("delete from app_runtime_channel where appId = #{appId}")
    int deleteRuntimeChannels(@Param("appId") Long appId);

    @Delete("delete from app_analytics_config where appId = #{appId}")
    int deleteAnalyticsConfigurations(@Param("appId") Long appId);

    @Delete("delete from app where id = #{appId}")
    int hardDeleteApp(@Param("appId") Long appId);
}
