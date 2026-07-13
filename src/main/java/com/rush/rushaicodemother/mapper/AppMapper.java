package com.rush.rushaicodemother.mapper;

import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.model.entity.App;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 应用映射层。
 */
public interface AppMapper extends BaseMapper<App> {

    /** 获取启动 Dev Server 所需的最小应用状态。 */
    @Select("select id, userId, codeGenType, devServerPort from app "
            + "where id = #{appId} and isDelete = 0")
    App selectDevServerTarget(@Param("appId") Long appId);

    /** 获取复制锁内需要重新确认的最新源应用状态。 */
    @Select("select id, appName, cover, initPrompt, codeGenType from app "
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
}
