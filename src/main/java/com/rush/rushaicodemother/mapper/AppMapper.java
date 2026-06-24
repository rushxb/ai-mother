package com.rush.rushaicodemother.mapper;

import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.model.entity.App;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 应用 映射层。
 *
 *
 */
public interface AppMapper extends BaseMapper<App> {

    @Delete("delete from app where id = #{id}")
    int hardDeleteById(@Param("id") Long id);
}
