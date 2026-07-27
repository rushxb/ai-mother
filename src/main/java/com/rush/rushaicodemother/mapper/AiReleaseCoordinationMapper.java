package com.rush.rushaicodemother.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 发布协调数据访问映射器。
 */
public interface AiReleaseCoordinationMapper {

    @Select("""
            SELECT lockName
            FROM ai_release_coordination_lock
            WHERE lockName = #{lockName}
            FOR UPDATE
            """)
    String lockByName(@Param("lockName") String lockName);
}
