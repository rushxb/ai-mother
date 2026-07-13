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

    /** 获取删除锁内需要重新确认的最新应用状态。 */
    @Select("select id, userId, codeGenType, deployKey from app "
            + "where id = #{appId} and isDelete = 0")
    App selectDeletionState(@Param("appId") Long appId);

    @Delete("delete from generation_model_call where appId = #{appId}")
    int deleteGenerationModelCalls(@Param("appId") Long appId);

    @Delete("delete from generation_build_log where appId = #{appId}")
    int deleteGenerationBuildLogs(@Param("appId") Long appId);

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
