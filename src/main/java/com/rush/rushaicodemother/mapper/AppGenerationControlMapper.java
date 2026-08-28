package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.AppGenerationControlEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 应用级生成控制的显式 SQL Mapper。 */
public interface AppGenerationControlMapper {

    @Select("""
            SELECT id, tenantId
            FROM app
            WHERE id = #{appId}
              AND isDelete = 0
            LIMIT 1
            """)
    App selectActiveApplication(@Param("appId") Long appId);

    @Select("""
            SELECT id, tenantId
            FROM app
            WHERE id = #{appId}
              AND isDelete = 0
            FOR UPDATE
            """)
    App lockActiveApplication(@Param("appId") Long appId);

    @Select("""
            SELECT appId, generationPaused, emergencyStopped, maxConcurrentTasks,
                   modelPolicy, dependencyMutationPolicy, dependencyNetworkPolicy,
                   dangerousToolPolicy, monthlyCreditLimit, version, updatedBy,
                   createTime, updateTime
            FROM app_generation_control
            WHERE appId = #{appId}
            LIMIT 1
            """)
    AppGenerationControlEntity selectByAppId(@Param("appId") Long appId);

    @Insert("""
            INSERT INTO app_generation_control (
                appId, generationPaused, emergencyStopped, maxConcurrentTasks,
                modelPolicy, dependencyMutationPolicy, dependencyNetworkPolicy,
                dangerousToolPolicy, monthlyCreditLimit, version, updatedBy,
                createTime, updateTime
            ) VALUES (
                #{appId}, #{generationPaused}, #{emergencyStopped}, #{maxConcurrentTasks},
                #{modelPolicy}, #{dependencyMutationPolicy}, #{dependencyNetworkPolicy},
                #{dangerousToolPolicy}, #{monthlyCreditLimit}, #{version}, #{updatedBy},
                #{createTime}, #{updateTime}
            )
            """)
    int insert(AppGenerationControlEntity entity);

    @Update("""
            UPDATE app_generation_control
            SET generationPaused = #{entity.generationPaused},
                emergencyStopped = #{entity.emergencyStopped},
                maxConcurrentTasks = #{entity.maxConcurrentTasks},
                modelPolicy = #{entity.modelPolicy},
                dependencyMutationPolicy = #{entity.dependencyMutationPolicy},
                dependencyNetworkPolicy = #{entity.dependencyNetworkPolicy},
                dangerousToolPolicy = #{entity.dangerousToolPolicy},
                monthlyCreditLimit = #{entity.monthlyCreditLimit},
                version = version + 1,
                updatedBy = #{entity.updatedBy},
                updateTime = #{entity.updateTime}
            WHERE appId = #{entity.appId}
              AND version = #{expectedVersion}
            """)
    int update(@Param("entity") AppGenerationControlEntity entity,
               @Param("expectedVersion") long expectedVersion);
}
