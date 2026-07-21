package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GenerationBenchmarkUsageMapper {

    @Select("""
            SELECT totalTokens, creditCost
            FROM generation_task
            WHERE taskId = #{taskId}
              AND isDelete = 0
            LIMIT 1
            """)
    GenerationTask selectUsageByTaskId(@Param("taskId") String taskId);
}
